package com.imin.iminapi.service.event;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure sell-through milestone math, isolated from JPA so it can be unit-tested.
 *
 * <p>A tier "reaches" threshold {@code T} when its floor sell-through percentage
 * is {@code >= T}. We track three: 50, 80, 100.
 */
public final class SalesMilestones {

    /** Ascending — order matters: callers fire the highest newly-reached one. */
    public static final int[] THRESHOLDS = {50, 80, 100};

    private SalesMilestones() {}

    /**
     * The thresholds (ascending) that {@code sold}/{@code capacity} currently
     * satisfies. Empty when capacity is non-positive (nothing to sell out of).
     */
    public static List<Integer> satisfied(int sold, int capacity) {
        List<Integer> hit = new ArrayList<>();
        if (capacity <= 0) return hit;
        int pct = (int) ((long) sold * 100 / capacity); // floor
        for (int t : THRESHOLDS) {
            if (pct >= t) hit.add(t);
        }
        return hit;
    }
}
