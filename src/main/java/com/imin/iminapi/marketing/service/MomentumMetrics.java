package com.imin.iminapi.marketing.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure sales-curve metrics for one event at evaluation time (spec §6.1 inputs).
 * No I/O — the evaluator fetches raw counts/timestamps and calls compute().
 *
 * <p>This record is the FROZEN EVIDENCE for why a suggestion fired: it is serialized
 * into {@code momentum_suggestions.metrics_snapshot} once, at evaluation, and never
 * rewritten. The card's prose (headline / pace / daysOutLabel) is derived from these
 * numbers on read by {@link MomentumProse} — a pure function of a frozen input, so it
 * cannot drift from the numbers it describes.
 *
 * <h2>Two velocities, two precisions — read this before using them</h2>
 * <ul>
 *   <li>{@link #velocity7d()} is <b>ORDERS</b>/day (7-day order arrivals ÷ 7). It is the
 *       input to the existing slump rule and is left EXACTLY as-is so trigger firing does
 *       not change. Note it is compared in {@code MomentumEvaluator.pickTrigger} against
 *       {@link #requiredVelocityToTarget(int)}, which is TICKETS/day — a pre-existing unit
 *       mismatch, reported not silently "fixed", because changing it would change which
 *       suggestions fire.</li>
 *   <li>{@link #ticketsPerDay7d()} is <b>TICKETS</b>/day, summed from the real
 *       {@link #spark()} series. It is prose-only (never a trigger input) and is what the
 *       card means by "selling N/day" — the figure sits next to "487/600 sold", so it has
 *       to be tickets.</li>
 *   <li>{@link #hoursToStart()} is the true hours-to-doors. {@code pickTrigger} still uses
 *       the coarse {@code daysOut * 24} it always used (unchanged firing); this field
 *       exists so "Doors in 64h" can be true rather than rounded to "Doors in 48h".</li>
 * </ul>
 */
public record MomentumMetrics(
        int sold,
        int capacity,
        int sellThroughPct,
        int daysOut,
        long hoursSinceOnSale,
        double velocity7d,
        /** True hours until doors. Prose-only — see the class note. */
        long hoursToStart,
        /**
         * REAL tickets sold per UTC day, oldest → newest, one point per day, zero-filled.
         * Sourced from the {@code tickets} table ({@code TicketRepository.findSoldCreatedAtSince}).
         * Empty when the event has no sold tickets in the window — never synthesized from a
         * scalar, because a series faked from one number can only ever draw the shape the
         * fake implies (that bug is exactly what this field exists to kill).
         */
        List<Integer> spark,
        /** UTC date that {@code spark[0]} covers; null when {@code spark} is empty. */
        LocalDate sparkStartDate,
        /** TICKETS/day over the last 7 spark points. Prose-only — see the class note. */
        double ticketsPerDay7d) {

    /** Days of daily history the spark carries. The prototype card ships 7–10 points. */
    public static final int SPARK_DAYS = 10;

    public static MomentumMetrics compute(
            int sold,
            int capacity,
            int ordersLast7d,
            Instant onSaleAt,
            Instant startsAt,
            Instant now) {
        return compute(sold, capacity, ordersLast7d, onSaleAt, startsAt, now, List.of());
    }

    /**
     * @param soldTicketCreatedAt {@code created_at} of every SOLD ticket in the spark
     *                            window, from the tickets table. Pass {@link List#of()}
     *                            when genuinely unavailable — the series is then empty,
     *                            not invented.
     */
    public static MomentumMetrics compute(
            int sold,
            int capacity,
            int ordersLast7d,
            Instant onSaleAt,
            Instant startsAt,
            Instant now,
            List<Instant> soldTicketCreatedAt) {

        int sellThrough = capacity <= 0 ? 0 : (int) Math.round(sold * 100.0 / capacity);
        int daysOut = startsAt == null ? 0 : (int) Math.max(0, Duration.between(now, startsAt).toDays());
        long hoursToStart = startsAt == null ? 0 : Math.max(0, Duration.between(now, startsAt).toHours());
        long hoursSinceOnSale = onSaleAt == null ? 0 : Math.max(0, Duration.between(onSaleAt, now).toHours());
        double velocity = ordersLast7d / 7.0;

        List<Integer> spark = dailySpark(soldTicketCreatedAt, onSaleAt, now);
        LocalDate sparkStart = spark.isEmpty() ? null : windowStart(onSaleAt, now);
        double ticketsPerDay = ticketsPerDay(spark);

        return new MomentumMetrics(sold, capacity, sellThrough, daysOut, hoursSinceOnSale,
                velocity, hoursToStart, spark, sparkStart, ticketsPerDay);
    }

    /**
     * Bucket SOLD-ticket timestamps into one count per UTC day, oldest → newest, zero-filled.
     *
     * <p>The window ends on {@code now}'s UTC day and spans {@link #SPARK_DAYS} days, clamped
     * so it never starts before {@code onSaleAt}'s day — a run of leading zeros from before
     * tickets could be bought is noise, not signal. Days INSIDE the window with no sales stay
     * as real zeros: a zero day is evidence, and it is how a decline gets drawn.
     */
    public static List<Integer> dailySpark(List<Instant> soldTicketCreatedAt, Instant onSaleAt, Instant now) {
        if (soldTicketCreatedAt == null || soldTicketCreatedAt.isEmpty()) return List.of();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate start = windowStart(onSaleAt, now);
        int points = (int) (today.toEpochDay() - start.toEpochDay()) + 1;

        int[] counts = new int[points];
        for (Instant at : soldTicketCreatedAt) {
            if (at == null) continue;
            int idx = (int) (at.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() - start.toEpochDay());
            if (idx >= 0 && idx < points) counts[idx]++;
        }
        List<Integer> out = new ArrayList<>(points);
        for (int c : counts) out.add(c);
        return List.copyOf(out);
    }

    /** First UTC day of the spark window: {@code SPARK_DAYS} back, but never before on-sale. */
    private static LocalDate windowStart(Instant onSaleAt, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate start = today.minusDays(SPARK_DAYS - 1L);
        if (onSaleAt != null) {
            LocalDate onSaleDay = onSaleAt.atZone(ZoneOffset.UTC).toLocalDate();
            if (onSaleDay.isAfter(start)) start = onSaleDay;
        }
        return start.isAfter(today) ? today : start;
    }

    /** The spark's trailing-7-day mean, in TICKETS/day. 0 when there is no series. */
    private static double ticketsPerDay(List<Integer> spark) {
        if (spark.isEmpty()) return 0.0;
        int window = Math.min(7, spark.size());
        int sum = 0;
        for (int i = spark.size() - window; i < spark.size(); i++) sum += spark.get(i);
        return sum / (double) window;
    }

    /** The spark's first point vs its last — the raw material of a "pace is cooling" claim. */
    public boolean hasSpark() {
        return spark != null && !spark.isEmpty();
    }

    /** Tickets/day needed to reach {@code targetPct} of capacity by the event date. */
    public double requiredVelocityToTarget(int targetPct) {
        if (daysOut <= 0) return Double.POSITIVE_INFINITY;
        double target = capacity * (targetPct / 100.0);
        double remaining = Math.max(0, target - sold);
        return remaining / daysOut;
    }

    /** Tickets still unsold. Never negative (an oversold tier would otherwise read as < 0). */
    public int remaining() {
        return Math.max(0, capacity - sold);
    }

    /**
     * Sell-through the event lands on if {@link #ticketsPerDay7d()} holds to doors, as a
     * percentage of capacity, clamped at 100. Used by the slump card's "at this pace you
     * finish ~74%" line. Returns the current sell-through when there is no real series to
     * project from — a projection without a velocity would be a guess.
     */
    public int projectedSellThroughPct() {
        if (capacity <= 0 || !hasSpark()) return sellThroughPct;
        double projected = sold + (ticketsPerDay7d * Math.max(0, daysOut));
        return (int) Math.min(100, Math.round(projected * 100.0 / capacity));
    }

    /** JSON for metrics_snapshot (audit + "why am I seeing this"). */
    public String toJson() {
        return "{"
                + "\"sold\":" + sold + ","
                + "\"capacity\":" + capacity + ","
                + "\"sellThroughPct\":" + sellThroughPct + ","
                + "\"daysOut\":" + daysOut + ","
                + "\"hoursSinceOnSale\":" + hoursSinceOnSale + ","
                + "\"velocity7d\":" + fmt(velocity7d) + ","
                + "\"hoursToStart\":" + hoursToStart + ","
                + "\"ticketsPerDay7d\":" + fmt(ticketsPerDay7d) + ","
                + "\"sparkStartDate\":" + (sparkStartDate == null ? "null" : "\"" + sparkStartDate + "\"") + ","
                + "\"spark\":" + sparkJson()
                + "}";
    }

    private String sparkJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < spark.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(spark.get(i));
        }
        return sb.append(']').toString();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
