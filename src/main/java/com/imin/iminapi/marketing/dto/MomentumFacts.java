package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.service.MomentumMetrics;

import java.util.List;

/**
 * The numbers a suggestion card words in the organizer's language, restated from the frozen metrics snapshot.
 * The webapp builds the headline, time label and pace sentence from these; the api sends no prose.
 *
 * @param ticketsPerDay7d         tickets per day over the spark's last 7 points; null without a real series
 * @param projectedSellThroughPct sell-through at doors if that pace holds (capped at 100); null without a series
 *                                or a capacity
 * @param declinePct              how far the later half of the spark fell below the earlier half; null when the
 *                                series is shorter than 4 points or did not fall
 * @param declineDays             the length of the earlier half {@code declinePct} compares against
 * @param clickedNoTicket         {@code clicked_no_buy}: the clickers without a ticket at evaluation; null otherwise
 */
public record MomentumFacts(
        int sold,
        int capacity,
        int sellThroughPct,
        int seatsLeft,
        int seatsGone,
        int daysOut,
        long hoursToStart,
        long hoursSinceOnSale,
        Double ticketsPerDay7d,
        Integer projectedSellThroughPct,
        Integer declinePct,
        Integer declineDays,
        Integer clickedNoTicket) {

    public static MomentumFacts from(MomentumMetrics m, Integer clickedNoTicket) {
        boolean series = m.hasSpark();
        Integer decline = declinePct(m.spark());
        return new MomentumFacts(
                m.sold(), m.capacity(), m.sellThroughPct(), m.remaining(), m.seatsGone(),
                m.daysOut(), m.hoursToStart(), m.hoursSinceOnSale(),
                series ? m.ticketsPerDay7d() : null,
                series && m.capacity() > 0 ? m.projectedSellThroughPct() : null,
                decline,
                decline == null ? null : m.spark().size() / 2,
                clickedNoTicket);
    }

    /** The trailing half of the spark against its earlier half; null unless the data shows a real drop. */
    private static Integer declinePct(List<Integer> spark) {
        if (spark == null || spark.size() < 4) return null;
        int half = spark.size() / 2;
        double earlier = mean(spark.subList(0, half));
        double later = mean(spark.subList(half, spark.size()));
        if (earlier <= 0) return null;
        // A rise, a flat line or a drop that rounds to 0 % is no decline.
        int drop = (int) Math.round((earlier - later) / earlier * 100.0);
        return drop > 0 ? drop : null;
    }

    private static double mean(List<Integer> xs) {
        int sum = 0;
        for (int x : xs) sum += x;
        return xs.isEmpty() ? 0 : sum / (double) xs.size();
    }
}
