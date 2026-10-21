package com.imin.iminapi.service.ticket;

import com.imin.iminapi.audience.service.MarketingOptedOutCheck;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.ticket.google.GoogleWalletPassService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReminderEmailComposerTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final EventReminderSender.Window DAY = EventReminderSender.Window.T24H;
    private static final EventReminderSender.Window DOORS = EventReminderSender.Window.T3H;

    private AppleWalletPassService apple;
    private GoogleWalletPassService google;
    private TicketTierRepository tiers;
    private ModerationHoldRepository holds;
    private MarketingOptedOutCheck optOuts;
    private ReminderEmailComposer composer;
    private Event event;
    private Organization org;

    @BeforeEach
    void setUp() {
        apple = mock(AppleWalletPassService.class);
        google = mock(GoogleWalletPassService.class);
        tiers = mock(TicketTierRepository.class);
        holds = mock(ModerationHoldRepository.class);
        optOuts = mock(MarketingOptedOutCheck.class);
        TicketProperties ticketProps = new TicketProperties();
        ticketProps.setApiPublicBaseUrl("https://api.example.test");
        EmailProperties emailProps = new EmailProperties();
        emailProps.setBuyerSiteBaseUrl("https://app.example.test");
        composer = new ReminderEmailComposer(new EmailTemplateRenderer(),
                new WalletOffers(apple, google, ticketProps), new ReminderShareLink(emailProps),
                tiers, holds, optOuts, Clock.fixed(NOW, ZoneOffset.UTC));

        event = new Event();
        event.setId(UUID.randomUUID());
        event.setOrgId(UUID.randomUUID());
        event.setName("Vechirka");
        event.setStatus(EventStatus.LIVE);
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStartsAt(Instant.parse("2026-10-25T10:00:00Z"));
        event.setTimezone("Europe/Paris");
        event.setVenueName("Salle Aurore");
        event.setVenueCity("Paris");
        event.setRemindersEnabled(true);
        event.setReminderShareEnabled(true);
        org = new Organization();
        org.setName("Aurore Crew");

        TicketTier open = new TicketTier();
        open.setEventId(event.getId());
        open.setEnabled(true);
        open.setQuantity(10);
        when(tiers.findByEventIdOrderBySortOrderAsc(event.getId())).thenReturn(List.of(open));
    }

    private static Ticket ticket(String state, String tier) {
        Ticket t = new Ticket();
        t.setToken("TKT_" + UUID.randomUUID());
        t.setState(state);
        t.setTierName(tier);
        return t;
    }

    private ReminderEmailComposer.Composed compose(List<Ticket> tickets, EventReminderSender.Window w, String locale,
                                                   boolean friend) {
        return composer.compose(event, org, tickets, "https://app.example.test/order/ORD_SECRET", w, locale, friend);
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true", "false,false"})
    void walletRowsOnlyForLiveTicketsAndOnlyForWalletsThatWork(boolean appleOn, boolean googleOn) {
        when(apple.isConfigured()).thenReturn(appleOn);
        when(google.isConfigured()).thenReturn(googleOn);
        Ticket live = ticket("issued", "Early bird");
        Ticket refunded = ticket("refunded", "Refunded tier");

        var mail = compose(List.of(live, refunded), DAY, "en", false);

        assertThat(mail.text().contains("/apple-wallet.pkpass")).isEqualTo(appleOn);
        assertThat(mail.text().contains("/google-wallet")).isEqualTo(googleOn);
        assertThat(mail.html().contains("/apple-wallet.pkpass")).isEqualTo(appleOn);
        assertThat(mail.text().contains("Early bird")).isEqualTo(appleOn || googleOn);
        assertThat(mail.text()).doesNotContain(refunded.getToken()).doesNotContain("Refunded tier");
        if (appleOn) assertThat(mail.html()).contains("Add to Apple Wallet");
        if (googleOn) assertThat(mail.html()).contains("Save to Google Wallet");
    }

    @ParameterizedTest
    @CsvSource({
            "all conditions hold,true",
            "private event,false",
            "nothing purchasable,false",
            "sales suspended,false",
            "organizer switch off,false",
            "three hour window,false"})
    void friendBlockOnlyWhenEveryConditionHolds(String scenario, boolean expected) {
        var window = DAY;
        switch (scenario) {
            case "private event" -> event.setVisibility(EventVisibility.PRIVATE);
            case "nothing purchasable" -> when(tiers.findByEventIdOrderBySortOrderAsc(event.getId())).thenReturn(List.of());
            case "sales suspended" -> when(holds.salesSuspended(event.getOrgId(), event.getId())).thenReturn(true);
            case "organizer switch off" -> event.setReminderShareEnabled(false);
            case "three hour window" -> window = DOORS;
            default -> { }
        }

        assertThat(composer.friendBlockEligible(event, window, "buyer@example.test")).isEqualTo(expected);
    }

    @Test
    void anOrderWithNoAddressNeverGetsTheFriendBlock() {
        assertThat(composer.friendBlockEligible(event, DAY, null)).isFalse();
        assertThat(composer.friendBlockEligible(event, DAY)).isTrue();
    }

    @Test
    void friendBlockShowsOnlyInTheDayBeforeEmail() {
        String day = compose(List.of(ticket("issued", "GA")), DAY, "en", true).text();
        String doors = compose(List.of(ticket("issued", "GA")), DOORS, "en", true).text();

        assertThat(day).contains("Going with friends?").contains("wa.me").contains("t.me/share/url");
        assertThat(doors).doesNotContain("Going with friends?").doesNotContain("wa.me");
    }

    @Test
    void friendLinksNeverCarryOrderOrTicketTokens() {
        Ticket t = ticket("issued", "GA");
        var mail = compose(List.of(t), DAY, "en", true);

        String friend = mail.text().substring(mail.text().indexOf("Going with friends?"));
        assertThat(friend).doesNotContain("ORD_SECRET").doesNotContain(t.getToken()).doesNotContain("/order/");
        String decoded = java.net.URLDecoder.decode(friend, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(decoded).contains("https://app.example.test/e/" + event.getId()
                + "?utm_source=friends&utm_medium=share&utm_campaign=reminder");
    }

    @Test
    void doorNoteRendersAsPlainText() {
        event.setReminderDoorNote("<b>Doors 23:00</b> & ID \"required\"");

        var mail = compose(List.of(ticket("issued", "GA")), DAY, "en", false);

        assertThat(mail.html()).contains("&lt;b&gt;Doors 23:00&lt;/b&gt; &amp; ID &quot;required&quot;")
                .doesNotContain("<b>Doors");
        assertThat(mail.text()).contains("<b>Doors 23:00</b> & ID \"required\"").contains("From Aurore Crew");
    }

    /** 22:30Z on the 24th is already the 25th, 00:30, in Paris. */
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "en;Sun 25 Oct · 00:30", "fr;dim. 25 oct. · 00:30", "uk;нд, 25 жовт. · 00:30"})
    void dateIsTheEventsLocalDayInTheBuyersLanguage(String locale, String expected) {
        event.setStartsAt(Instant.parse("2026-10-24T22:30:00Z"));

        assertThat(ReminderEmailComposer.formatWhen(event, locale)).isEqualTo(expected);
        assertThat(compose(List.of(ticket("issued", "GA")), DAY, locale, false).text()).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({"1,квиток", "2,квитки", "5,квитків", "11,квитків", "21,квиток", "22,квитки", "14,квитків"})
    void ukrainianTicketCountUsesTheRightPluralForm(int n, String noun) {
        assertThat(ReminderEmailComposer.ukTickets(n)).isEqualTo(noun);
        List<Ticket> tickets = java.util.stream.IntStream.range(0, n).mapToObj(i -> ticket("issued", "GA")).toList();
        String text = compose(tickets, DAY, "uk", false).text();
        assertThat(text).contains(n == 1 ? "Твій квиток готовий." : n + " " + noun + ".");
    }

    @Test
    void directionsFallBackToTheTypedAddressWhenNotGeocoded() {
        var mail = compose(List.of(ticket("issued", "GA")), DOORS, "fr", false);

        assertThat(mail.text()).contains("Itinéraire\u00a0: https://www.google.com/maps/search/?api=1&query=Salle%20Aurore%2C%20Paris");
    }

    @ParameterizedTest
    @EnumSource(EventReminderSender.Window.class)
    void everyLocaleTemplateRendersWithoutLeftoverPlaceholders(EventReminderSender.Window w) {
        for (String locale : List.of("en", "es", "fr", "uk")) {
            var mail = compose(List.of(ticket("issued", "GA")), w, locale, true);
            assertThat(mail.html()).doesNotContain("{{").doesNotContain("__REMINDER_EXTRAS__");
            assertThat(mail.text()).doesNotContain("{{").doesNotContain("__REMINDER_EXTRAS__");
        }
    }
}
