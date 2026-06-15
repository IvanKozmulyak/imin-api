package com.imin.iminapi.dto.event;

import java.util.List;

/**
 * One snapshot of a single event's live sales dashboard: headline tiles, the
 * per-tier breakdown (+ top-converting ordering), and the conversion funnel.
 * All {@code *Pct} fields are 0–100 doubles.
 */
public record SalesDashboardResponse(
        String currency,
        Tiles tiles,
        List<TierBreakdown> tiers,
        List<TierBreakdown> topConvertingTiers,
        Funnel funnel) {

    public record Tiles(
            int ticketsSold,
            long netRevenueMinor,
            long grossRevenueMinor,
            int capacity,
            double capacityPct,
            int checkedIn,
            double checkInRatePct) {}

    public record TierBreakdown(
            String tierId,
            String name,
            int sold,
            int redeemed,
            long grossRevenueMinor,
            int quantity,
            double sellThroughPct) {}

    public record Funnel(List<Stage> stages, List<DropOff> dropOff) {

        public record Stage(String stage, long count) {}

        public record DropOff(String from, String to, long lostCount, double lostPct) {}
    }
}
