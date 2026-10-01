package com.imin.iminapi.predictor.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Pure milestone math for the predictor's scheduled re-runs, isolated from JPA so it is unit-testable.
 *
 * <p>Sell-through: the event-level 25/50/75% crossings that trigger a re-forecast (spec §4.2). Kept SEPARATE
 * from {@code SalesMilestones} (the 50/80/100% per-tier buyer notifications) because these are event-level and
 * use different thresholds. A crossing is computed "on the transition": given the sold count at the previous
 * re-forecast and the sold count now, the thresholds newly reached are those the new count satisfies that the
 * old count did not, so the trigger fires once per crossing independent of the burst-debounce.
 *
 * <p>Radar: the days-out milestones (30/14/7/2 days before the event's night) at which {@code RadarJob}
 * re-runs an event's date check. A milestone stays due from its day until the next one opens, so a missed
 * daily run catches up the next day and runs only once. The two constant sets are unrelated.
 */
public final class ReforecastMilestones {

    /** Event-level sell-through thresholds that warrant a re-forecast, ascending. */
    public static final int[] THRESHOLDS = {25, 50, 75};

    /** Radar days-out milestones, descending. Must equal the V167 CHECK {@code radar_milestone IN (2, 7, 14, 30)}. */
    public static final List<Integer> RADAR_DAYS_OUT = List.of(30, 14, 7, 2);

    private ReforecastMilestones() {}

    /**
     * The thresholds (25/50/75) that {@code newSold} satisfies but {@code prevSold} did not,
     * as floor sell-through percentages of {@code capacity}. Empty when capacity is
     * non-positive or nothing new crossed.
     */
    public static List<Integer> newlyCrossed(int prevSold, int newSold, int capacity) {
        List<Integer> crossed = new ArrayList<>();
        if (capacity <= 0) return crossed;
        int prevPct = (int) ((long) Math.max(0, prevSold) * 100 / capacity);
        int newPct = (int) ((long) Math.max(0, newSold) * 100 / capacity);
        for (int t : THRESHOLDS) {
            if (newPct >= t && prevPct < t) crossed.add(t);
        }
        return crossed;
    }

    /** The smallest radar milestone at or above {@code daysOut}; empty on or after the night and beyond 30 days. */
    public static OptionalInt radarMilestoneDue(long daysOut) {
        if (daysOut < 1) return OptionalInt.empty();
        OptionalInt due = OptionalInt.empty();
        for (int m : RADAR_DAYS_OUT) {
            if (m >= daysOut) due = OptionalInt.of(m);
        }
        return due;
    }

    /** True when the last check day falls on or after the day the milestone's window opened. */
    public static boolean radarDone(LocalDate lastCheckedDay, LocalDate night, int milestone) {
        return !lastCheckedDay.isBefore(night.minusDays(milestone));
    }
}
