package com.imin.iminapi.marketing.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure sales-curve metrics for one event at evaluation time (spec §6.1 inputs).
 * No I/O — the evaluator fetches raw counts/timestamps and calls compute().
 */
public record MomentumMetrics(
        int sold,
        int capacity,
        int sellThroughPct,
        int daysOut,
        long hoursSinceOnSale,
        double velocity7d) {

    public static MomentumMetrics compute(
            int sold,
            int capacity,
            int ordersLast7d,
            Instant onSaleAt,
            Instant startsAt,
            Instant now) {

        int sellThrough = capacity <= 0 ? 0 : (int) Math.round(sold * 100.0 / capacity);
        int daysOut = startsAt == null ? 0 : (int) Math.max(0, Duration.between(now, startsAt).toDays());
        long hoursSinceOnSale = onSaleAt == null ? 0 : Math.max(0, Duration.between(onSaleAt, now).toHours());
        double velocity = ordersLast7d / 7.0;

        return new MomentumMetrics(sold, capacity, sellThrough, daysOut, hoursSinceOnSale, velocity);
    }

    /** Tickets/day needed to reach {@code targetPct} of capacity by the event date. */
    public double requiredVelocityToTarget(int targetPct) {
        if (daysOut <= 0) return Double.POSITIVE_INFINITY;
        double target = capacity * (targetPct / 100.0);
        double remaining = Math.max(0, target - sold);
        return remaining / daysOut;
    }

    /** JSON for metrics_snapshot (audit + "why am I seeing this"). */
    public String toJson() {
        return "{"
                + "\"sold\":" + sold + ","
                + "\"capacity\":" + capacity + ","
                + "\"sellThroughPct\":" + sellThroughPct + ","
                + "\"daysOut\":" + daysOut + ","
                + "\"hoursSinceOnSale\":" + hoursSinceOnSale + ","
                + "\"velocity7d\":" + String.format(java.util.Locale.ROOT, "%.2f", velocity7d)
                + "}";
    }
}
