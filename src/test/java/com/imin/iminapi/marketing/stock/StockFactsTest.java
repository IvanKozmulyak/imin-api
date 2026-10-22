package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.marketing.model.StockAlertReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure stock-alert rules: what makes "last tickets" and "the price goes up" true of a tier. */
class StockFactsTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final UUID EARLY = UUID.randomUUID();
    private static final UUID REGULAR = UUID.randomUUID();
    private static final UUID STUDENT = UUID.randomUUID();
    private static final Instant EARLY_CLOSE = NOW.plus(Duration.ofHours(20));

    private static StockFacts.Tier tier(UUID id, String name, boolean enabled, int quantity, int remaining,
                                        Instant starts, Instant closes, int sortOrder, long allIn) {
        return new StockFacts.Tier(id, name, enabled, quantity, remaining, starts, closes, sortOrder, allIn);
    }

    private static StockFacts.Snapshot snap(boolean onSale, Instant eventClose, StockFacts.Tier... tiers) {
        return new StockFacts.Snapshot(onSale, eventClose, "Europe/Paris", "EUR", List.of(tiers), NOW);
    }

    // ---- last tickets ----

    @ParameterizedTest(name = "quantity {0} -> {1}")
    @CsvSource({"100, 20", "7, 2", "1, 1", "15, 3", "35, 7", "200, 30", "500, 30", "0, 0"})
    void autoThreshold_isTheDashboardsAlmostGoneRule(int quantity, int expected) {
        assertThat(StockFacts.autoThreshold(quantity)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "fixed {0} on {1} -> {2}")
    @CsvSource(nullValues = "null", value = {"null, 40, 8", "5, 40, 5", "50, 40, 8", "50, 200, 30"})
    void fixedThreshold_onlyLowersTheTiersAutoRule(Integer fixed, int quantity, int expected) {
        assertThat(StockFacts.threshold(fixed, quantity)).isEqualTo(expected);
    }

    static Stream<Arguments> lastTickets() {
        return Stream.of(
                // 20 of 100 left with holds counted: exactly at the auto threshold, true.
                Arguments.of("at threshold", tier(EARLY, "GA", true, 100, 20, null, null, 0, 1500), true, 20, null),
                Arguments.of("one left", tier(EARLY, "GA", true, 100, 1, null, null, 0, 1500), true, 20, null),
                // A threshold stored above the tier's rule (fixed 50) never makes a full or nearly full tier "last".
                Arguments.of("fixed 50, all 40 left", tier(EARLY, "GA", true, 40, 40, null, null, 0, 1500), true, 50,
                        StockAlertReason.MORE_LEFT),
                Arguments.of("fixed 50, 39 of 40 left", tier(EARLY, "GA", true, 40, 39, null, null, 0, 1500), true, 50,
                        StockAlertReason.MORE_LEFT),
                // Nothing sold or held yet: a one-ticket tier is not "last tickets".
                Arguments.of("one-ticket tier untouched", tier(EARLY, "GA", true, 1, 1, null, null, 0, 1500), true, 1,
                        StockAlertReason.MORE_LEFT),
                // 120 left must never read as "last tickets".
                Arguments.of("more left", tier(EARLY, "GA", true, 200, 120, null, null, 0, 1500), true, 30,
                        StockAlertReason.MORE_LEFT),
                Arguments.of("sold out", tier(EARLY, "GA", true, 100, 0, null, null, 0, 1500), true, 20,
                        StockAlertReason.SOLD_OUT),
                Arguments.of("switched off", tier(EARLY, "GA", false, 100, 5, null, null, 0, 1500), true, 20,
                        StockAlertReason.TIER_OFF),
                Arguments.of("not on sale yet", tier(EARLY, "GA", true, 100, 5, NOW.plusSeconds(60), null, 0, 1500),
                        true, 20, StockAlertReason.TIER_OFF),
                Arguments.of("tier closed", tier(EARLY, "GA", true, 100, 5, null, NOW, 0, 1500), true, 20,
                        StockAlertReason.DEADLINE_PASSED),
                Arguments.of("event not on sale", tier(EARLY, "GA", true, 100, 5, null, null, 0, 1500), false, 20,
                        StockAlertReason.EVENT_NOT_ON_SALE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lastTickets")
    void lastTickets_holdsOnlyForABuyableTierWithOneToThresholdLeft(String name, StockFacts.Tier t, boolean onSale,
                                                                    int threshold, String reason) {
        assertThat(StockFacts.lastTicketsBlocker(snap(onSale, null, t), t.id(), threshold))
                .isEqualTo(Optional.ofNullable(reason));
    }

    @Test
    void lastTickets_aMissingTierIsOff() {
        assertThat(StockFacts.lastTicketsBlocker(snap(true, null), EARLY, 20))
                .contains(StockAlertReason.TIER_OFF);
    }

    // ---- price goes up ----

    private static StockFacts.Tier early() {
        return tier(EARLY, "Early bird", true, 100, 40, null, EARLY_CLOSE, 0, 1150);
    }

    private static StockFacts.Tier regular(long allIn) {
        return tier(REGULAR, "Regular", true, 300, 300, null, null, 1, allIn);
    }

    static Stream<Arguments> priceUp() {
        return Stream.of(
                Arguments.of("next tier costs more", snap(true, null, early(), regular(1625)), 24, null),
                Arguments.of("next tier costs the same", snap(true, null, early(), regular(1150)), 24,
                        StockAlertReason.NEXT_NOT_HIGHER),
                Arguments.of("next tier is cheaper", snap(true, null, early(), regular(900)), 24,
                        StockAlertReason.NEXT_NOT_HIGHER),
                // A cheaper tier still on sale after the change makes "the price goes up" untrue.
                Arguments.of("another cheaper tier stays", snap(true, null,
                        tier(STUDENT, "Student", true, 50, 50, null, null, -1, 800), early(), regular(1625)), 24,
                        StockAlertReason.NEXT_NOT_HIGHER),
                Arguments.of("next tier starts after a gap", snap(true, null, early(),
                        tier(REGULAR, "Regular", true, 300, 300, EARLY_CLOSE.plusSeconds(3600), null, 1, 1625)), 24,
                        StockAlertReason.NO_NEXT_TIER),
                Arguments.of("no next tier", snap(true, null, early()), 24, StockAlertReason.NO_NEXT_TIER),
                Arguments.of("next tier switched off", snap(true, null, early(),
                        tier(REGULAR, "Regular", false, 300, 300, null, null, 1, 1625)), 24,
                        StockAlertReason.NO_NEXT_TIER),
                Arguments.of("next tier sold out", snap(true, null, early(),
                        tier(REGULAR, "Regular", true, 300, 0, null, null, 1, 1625)), 24,
                        StockAlertReason.NO_NEXT_TIER),
                // The event's own sales end first: nothing goes up.
                Arguments.of("event sales end first", snap(true, EARLY_CLOSE, early(), regular(1625)), 24,
                        StockAlertReason.NO_NEXT_TIER),
                // 20 h to the change, lead 12 h: not yet.
                Arguments.of("outside the lead window", snap(true, null, early(), regular(1625)), 12,
                        StockAlertReason.DEADLINE_MOVED),
                Arguments.of("no lead window at send", snap(true, null, early(), regular(1625)), null, null),
                Arguments.of("tier without a close", snap(true, null,
                        tier(EARLY, "Early bird", true, 100, 40, null, null, 0, 1150), regular(1625)), 24,
                        StockAlertReason.DEADLINE_MOVED),
                Arguments.of("tier sold out", snap(true, null,
                        tier(EARLY, "Early bird", true, 100, 0, null, EARLY_CLOSE, 0, 1150), regular(1625)), 24,
                        StockAlertReason.SOLD_OUT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("priceUp")
    void priceUp_holdsOnlyWhenTheCheapestPriceAfterTheChangeIsHigher(String name, StockFacts.Snapshot s,
                                                                    Integer lead, String reason) {
        assertThat(StockFacts.priceUpBlocker(s, EARLY, lead)).isEqualTo(Optional.ofNullable(reason));
    }

    @Test
    void priceUp_quotesTheCheapestTierOnSaleAtTheChange_notTheNextInSortOrder() {
        UUID mid = UUID.randomUUID();
        // Regular follows in sort order, but Mid (cheaper, also on sale then) is what buyers will pay after the change.
        StockFacts.Snapshot s = snap(true, null, early(), regular(2000),
                tier(mid, "Mid", true, 100, 100, null, null, 2, 1625));
        assertThat(StockFacts.priceUpBlocker(s, EARLY, 24)).isEmpty();
        StockFacts f = StockFacts.of(s, StockAlertKind.PRICE_UP, EARLY, 20);
        assertThat(f.nextTierId()).isEqualTo(mid);
        assertThat(f.nextTierName()).isEqualTo("Mid");
        assertThat(f.nextPriceAllInMinor()).isEqualTo(1625);
    }

    @Test
    void priceUp_refusesWhenTwoTiersShareTheCheapestPriceAfterTheChange() {
        StockFacts.Snapshot s = snap(true, null, early(), regular(1625),
                tier(UUID.randomUUID(), "Door", true, 100, 100, null, null, 2, 1625));
        assertThat(StockFacts.priceUpBlocker(s, EARLY, 24)).contains(StockAlertReason.NEXT_TIER_UNCLEAR);
        assertThat(StockFacts.of(s, StockAlertKind.PRICE_UP, EARLY, 20).nextTierId()).isNull();
    }

    @Test
    void priceUpFacts_quoteTheTiersCloseAndTheNextTiersAllInPrice() {
        StockFacts f = StockFacts.of(snap(true, null, early(), regular(1625)), StockAlertKind.PRICE_UP, EARLY, 20);
        assertThat(f.closesAt()).isEqualTo(EARLY_CLOSE);
        assertThat(f.priceAllInMinor()).isEqualTo(1150);
        assertThat(f.nextTierId()).isEqualTo(REGULAR);
        assertThat(f.nextPriceAllInMinor()).isEqualTo(1625);
        assertThat(f.checkedAt()).isEqualTo(NOW);
    }

    // ---- frozen facts at send ----

    static Stream<Arguments> frozen() {
        StockFacts base = StockFacts.of(snap(true, null, early(), regular(1625)), StockAlertKind.PRICE_UP, EARLY, 20);
        List<Arguments> out = new ArrayList<>();
        out.add(Arguments.of("unchanged", base, null));
        out.add(Arguments.of("deadline moved later", copy(base, base.closesAt().plusSeconds(3600), base.priceAllInMinor(),
                base.nextPriceAllInMinor()), StockAlertReason.DEADLINE_MOVED));
        out.add(Arguments.of("current price changed", copy(base, base.closesAt(), 1200, base.nextPriceAllInMinor()),
                StockAlertReason.PRICE_CHANGED));
        out.add(Arguments.of("next price changed", copy(base, base.closesAt(), base.priceAllInMinor(), 1700L),
                StockAlertReason.PRICE_CHANGED));
        return out.stream();
    }

    private static StockFacts copy(StockFacts f, Instant closesAt, long price, Long next) {
        return new StockFacts(f.tierId(), f.tierName(), f.remaining(), f.quantity(), f.threshold(), price, f.nextTierId(),
                f.nextTierName(), next, closesAt, f.checkedAt(), f.timezone(), f.currency());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frozen")
    void frozenPriceUpEmail_isUntrueOnceAQuotedMomentOrPriceChanges(String name, StockFacts fresh, String reason) {
        StockFacts frozen = StockFacts.of(snap(true, null, early(), regular(1625)), StockAlertKind.PRICE_UP, EARLY, 20);
        assertThat(StockFacts.frozenMismatch(StockAlertKind.PRICE_UP, frozen, fresh))
                .isEqualTo(Optional.ofNullable(reason));
    }
}
