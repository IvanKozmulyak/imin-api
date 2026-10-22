package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.dto.publicapi.PublicTierDto;
import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.stripe.StripeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The numbers and dates a stock-alert email states, as fans read them. */
class StockFactTokensTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private static StockFacts priceUp(Instant closesAt, String zone) {
        return new StockFacts(UUID.randomUUID(), "Early bird", 40, 100, 20, 1150, UUID.randomUUID(), "Regular", 1625L,
                closesAt, NOW, zone, "EUR");
    }

    @Test
    void deadlineInEventZone_namesTheEventsDayNotUtcs() {
        // 23:30Z on Friday 6 Nov is already Saturday 00:30 in Paris.
        Map<String, String> v = StockFactTokens.values(StockAlertKind.PRICE_UP,
                priceUp(Instant.parse("2026-11-06T23:30:00Z"), "Europe/Paris"), "en");
        assertThat(v.get(StockFactTokens.PRICE_CHANGE_AT)).isEqualTo("Sat 7 Nov, 00:30");
        assertThat(v.get(StockFactTokens.STOCK_FACTS)).contains("until Sat 7 Nov, 00:30");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "en|1 ticket left",
            "es|queda 1 entrada",
            "fr|il reste 1 billet",
            "uk|залишився 1 квиток"})
    void lastTicketsBlock_saysOneTicketInTheSingular(String lang, String expected) {
        StockFacts f = new StockFacts(UUID.randomUUID(), "GA", 1, 100, 20, 1500, null, null, null, null, NOW,
                "Europe/Paris", "EUR");
        assertThat(StockFactTokens.values(StockAlertKind.LAST_TICKETS, f, lang).get(StockFactTokens.STOCK_FACTS))
                .contains(expected);
    }

    @ParameterizedTest(name = "uk {0}")
    @CsvSource(delimiter = '|', value = {
            "1|залишився 1 квиток", "2|залишилося 2 квитки", "4|залишилося 4 квитки", "5|залишилося 5 квитків",
            "11|залишилося 11 квитків", "14|залишилося 14 квитків", "21|залишився 21 квиток",
            "22|залишилося 22 квитки", "25|залишилося 25 квитків"})
    void ukrainianCount_takesEachPluralForm(int n, String expected) {
        assertThat(StockFactTokens.left(n, "uk", Locale.forLanguageTag("uk-UA"))).isEqualTo(expected);
    }

    @Test
    void frenchCount_isSingularForZeroAndOne() {
        assertThat(StockFactTokens.left(0, "fr", Locale.FRANCE)).isEqualTo("il reste 0 billet");
        assertThat(StockFactTokens.left(2, "fr", Locale.FRANCE)).isEqualTo("il reste 2 billets");
    }

    @Test
    void allInPrice_equalsTheEventPagesPrice_andAFreeTierShowsZero() {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setOrgId(UUID.randomUUID());
        e.setStatus(EventStatus.LIVE);
        e.setVisibility(EventVisibility.PUBLIC);
        e.setCurrency("EUR");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(NOW.plusSeconds(86_400 * 10));
        TicketTier paid = tier(e, "Early bird", 1000, 0);
        TicketTier free = tier(e, "Guest list", 0, 1);
        TicketTierRepository tiers = mock(TicketTierRepository.class);
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of(paid, free));
        StripeProperties stripe = new StripeProperties();
        stripe.setApplicationFeeBps(500);
        stripe.setApplicationFeeFixedMinor(99);
        StockFactsReader reader = new StockFactsReader(mock(EventRepository.class), tiers,
                mock(ModerationHoldRepository.class), stripe);

        StockFacts.Snapshot snap = reader.snapshot(e, NOW);

        long pagePrice = PublicTierDto.from(paid, e, NOW, 500, 99).priceAllInMinor();
        // 1000 + 5% (50) + 99 fixed = 1149: the price the event page and checkout show.
        assertThat(pagePrice).isEqualTo(1149L);
        assertThat(snap.tier(paid.getId()).orElseThrow().priceAllInMinor()).isEqualTo(pagePrice);
        assertThat(snap.tier(free.getId()).orElseThrow().priceAllInMinor()).isZero();
        StockFacts f = StockFacts.of(snap, StockAlertKind.LAST_TICKETS, paid.getId(), 20);
        assertThat(StockFactTokens.values(StockAlertKind.LAST_TICKETS, f, "en").get(StockFactTokens.PRICE_NOW))
                .isEqualTo("€11.49");
        StockFacts freeFacts = StockFacts.of(snap, StockAlertKind.LAST_TICKETS, free.getId(), 20);
        assertThat(StockFactTokens.values(StockAlertKind.LAST_TICKETS, freeFacts, "en").get(StockFactTokens.PRICE_NOW))
                .isEqualTo("€0.00");
    }

    @Test
    void apply_fillsEveryStockToken_andANullMapLeavesTextAlone() {
        Map<String, String> v = Map.of(StockFactTokens.TIER_NAME, "Early bird", StockFactTokens.TICKETS_LEFT, "12");
        assertThat(StockFactTokens.apply("{{ tier_name }}: {{tickets_left}} left {{price_next}}", v))
                .isEqualTo("Early bird: 12 left ");
        assertThat(StockFactTokens.apply("{{tier_name}}", null)).isEqualTo("{{tier_name}}");
    }

    private static TicketTier tier(Event e, String name, int price, int sortOrder) {
        TicketTier t = new TicketTier();
        t.setId(UUID.randomUUID());
        t.setEventId(e.getId());
        t.setName(name);
        t.setPriceMinor(price);
        t.setQuantity(100);
        t.setSortOrder(sortOrder);
        return t;
    }
}
