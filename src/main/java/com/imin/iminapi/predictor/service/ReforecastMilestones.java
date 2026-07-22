package com.imin.iminapi.predictor.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure event-level sold-through milestone math for the re-forecast triggers (spec §4.2, task
 * 86cav479j) — the 25/50/75% crossings. Isolated from JPA so it is unit-testable, and kept
 * SEPARATE from {@code SalesMilestones} (which drives the 50/80/100% per-tier buyer
 * notifications) because these are event-level and use different thresholds.
 *
 * <p>A crossing is computed "on the transition": given the sold count at the previous
 * re-forecast and the sold count now, the thresholds newly reached are those the new count
 * satisfies that the old count did not. This makes the milestone trigger fire once per crossing
 * (the new re-forecast advances the reference), independent of the burst-debounce.
 */
public final class ReforecastMilestones {

    /** Event-level sell-through thresholds that warrant a re-forecast, ascending. */
    public static final int[] THRESHOLDS = {25, 50, 75};

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
}
