package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.model.TicketTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ArmTimesTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId UTC = ZoneId.of("UTC");
    /** §5 fixture: Saturday 24.10.2026, doors 20:00 Paris. */
    private static final Instant START = at(2026, 10, 24, 20, 0);

    // ── d3 ─────────────────────────────────────────────────────────────────

    @Test
    void d3IsThreeDaysBeforeTheEventDateAt18InTheEventZone() {
        assertThat(ArmTimes.d3(START, PARIS)).isEqualTo(at(2026, 10, 21, 18, 0));
    }

    @Test
    void d3UsesTheEventDateInItsOwnZone_notTheUtcDate() {
        // 00:30 Paris on 25.10 is still 24.10 in UTC; the event date is the Paris one.
        Instant lateStart = at(2026, 10, 25, 0, 30);
        assertThat(ArmTimes.d3(lateStart, PARIS)).isEqualTo(at(2026, 10, 22, 18, 0));
    }

    // ── early bird ─────────────────────────────────────────────────────────

    @Test
    void earlyBirdTierClosingOn10OctoberGivesAnArmAt18ThatDay() {
        List<TicketTier> tiers = List.of(tier(1500, at(2026, 10, 10, 23, 59), true), tier(2500, null, true));
        assertThat(ArmTimes.earlyBirdEnd(tiers, START, PARIS)).contains(at(2026, 10, 10, 18, 0));
    }

    @Test
    void noSecondEnabledTier_meansNoEarlyBirdArm() {
        List<TicketTier> tiers = List.of(tier(1500, at(2026, 10, 10, 23, 59), true), tier(2500, null, false));
        assertThat(ArmTimes.earlyBirdEnd(tiers, START, PARIS)).isEmpty();
    }

    @Test
    void aSecondTierThatClosesNoLater_doesNotStayOnSale() {
        Instant close = at(2026, 10, 10, 23, 59);
        List<TicketTier> tiers = List.of(tier(1500, close, true), tier(2500, close, true));
        assertThat(ArmTimes.earlyBirdEnd(tiers, START, PARIS)).isEmpty();
    }

    @Test
    void aSecondTierClosingLater_staysOnSale() {
        List<TicketTier> tiers = List.of(tier(1500, at(2026, 10, 10, 23, 59), true),
                tier(2500, at(2026, 10, 24, 18, 0), true));
        assertThat(ArmTimes.earlyBirdEnd(tiers, START, PARIS)).contains(at(2026, 10, 10, 18, 0));
    }

    @Test
    void closingOnTheD3Date_meansNoEarlyBirdArm() {
        List<TicketTier> tiers = List.of(tier(1500, at(2026, 10, 21, 12, 0), true), tier(2500, null, true));
        assertThat(ArmTimes.earlyBirdEnd(tiers, START, PARIS)).isEmpty();
    }

    @Test
    void closingTheDayBeforeD3_isStillOffered() {
        List<TicketTier> tiers = List.of(tier(1500, at(2026, 10, 20, 12, 0), true), tier(2500, null, true));
        assertThat(ArmTimes.earlyBirdEnd(tiers, START, PARIS)).contains(at(2026, 10, 20, 18, 0));
    }

    @Test
    void theCheapestTierWithoutACloseDate_meansNoEarlyBirdArm() {
        List<TicketTier> tiers = List.of(tier(1500, null, true), tier(2500, at(2026, 10, 10, 23, 59), true));
        assertThat(ArmTimes.earlyBirdEnd(tiers, START, PARIS)).isEmpty();
    }

    @Test
    void aDisabledCheaperTier_isIgnored() {
        List<TicketTier> tiers = List.of(tier(500, at(2026, 10, 5, 23, 59), false),
                tier(1500, at(2026, 10, 10, 23, 59), true), tier(2500, null, true));
        assertThat(ArmTimes.earlyBirdEnd(tiers, START, PARIS)).contains(at(2026, 10, 10, 18, 0));
    }

    @Test
    void equalPrices_goToTheFirstTier() {
        List<TicketTier> tiers = List.of(tier(1500, at(2026, 10, 8, 23, 59), true),
                tier(1500, at(2026, 10, 12, 23, 59), true));
        assertThat(ArmTimes.earlyBirdEnd(tiers, START, PARIS)).contains(at(2026, 10, 8, 18, 0));
    }

    @Test
    void noTiers_meansNoEarlyBirdArm() {
        assertThat(ArmTimes.earlyBirdEnd(List.of(), START, PARIS)).isEqualTo(Optional.empty());
    }

    // ── quiet hours ────────────────────────────────────────────────────────

    @Test
    void anEveningInsideQuietHours_movesToNine_theNextMorning() {
        assertThat(ArmTimes.outOfQuietHours(at(2026, 10, 21, 22, 30), PARIS)).isEqualTo(at(2026, 10, 22, 9, 0));
    }

    @Test
    void twentyTwoSharp_isQuiet() {
        assertThat(ArmTimes.outOfQuietHours(at(2026, 10, 21, 22, 0), PARIS)).isEqualTo(at(2026, 10, 22, 9, 0));
    }

    @Test
    void anEarlyMorning_movesToNineTheSameDay() {
        assertThat(ArmTimes.outOfQuietHours(at(2026, 10, 21, 7, 0), PARIS)).isEqualTo(at(2026, 10, 21, 9, 0));
    }

    @Test
    void nineSharpAnd18_areLeftAlone() {
        assertThat(ArmTimes.outOfQuietHours(at(2026, 10, 21, 9, 0), PARIS)).isEqualTo(at(2026, 10, 21, 9, 0));
        assertThat(ArmTimes.outOfQuietHours(at(2026, 10, 21, 18, 0), PARIS)).isEqualTo(at(2026, 10, 21, 18, 0));
    }

    @Test
    void quietHoursAreReadInTheOrgZone() {
        // 18:00 Paris is 16:00 UTC: allowed for a UTC org; 23:00 Paris is 21:00 UTC: allowed there too.
        Instant late = at(2026, 10, 21, 23, 0);
        assertThat(ArmTimes.outOfQuietHours(late, UTC)).isEqualTo(late);
        assertThat(ArmTimes.outOfQuietHours(late, PARIS)).isEqualTo(at(2026, 10, 22, 9, 0));
    }

    private static Instant at(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(LocalDate.of(y, m, d), java.time.LocalTime.of(h, min)).atZone(PARIS).toInstant();
    }

    private static TicketTier tier(int priceMinor, Instant closes, boolean enabled) {
        TicketTier t = new TicketTier();
        t.setPriceMinor(priceMinor);
        t.setQuantity(100);
        t.setSaleClosesAt(closes);
        t.setEnabled(enabled);
        return t;
    }
}
