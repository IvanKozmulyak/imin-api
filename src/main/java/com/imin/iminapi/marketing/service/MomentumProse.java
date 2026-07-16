package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.model.MomentumTriggerType;

import java.util.List;
import java.util.Locale;

/**
 * The card's prose — {@code headline}, {@code pace}, {@code daysOutLabel} — derived from a
 * suggestion's metrics snapshot (spec §6.4; the prototype's {@code TRIGGERS} catalogue sets
 * the tone).
 *
 * <h2>Why this is derived on READ, not written at evaluation</h2>
 * Its only input is the metrics snapshot, which is frozen at evaluation and never rewritten.
 * A pure function of a frozen input yields the same answer whenever it runs — so deriving on
 * read cannot drift from the numbers, and it buys three things that snapshotting the strings
 * would not: suggestions written before this shipped still get prose, a wording fix reaches
 * live rows without a data migration, and the numbers stay the single source of truth.
 *
 * <h2>Truthfulness rules (non-negotiable)</h2>
 * Every sentence here is a restatement of a real number. There is NO fixed marketing copy: no
 * claim that is not a rendering of {@code sold}/{@code capacity}/{@code hoursToStart}/the real
 * spark series. Where a number is missing (e.g. no spark ⇒ no honest velocity), the clause that
 * would have used it is OMITTED rather than filled with a plausible-sounding default. The
 * persuasion in the card comes from {@code TRIGGERS[t].why} on the FE, which is static
 * trigger-explanation copy and is clearly not a metric.
 */
public final class MomentumProse {

    private MomentumProse() {}

    /**
     * Short all-caps banner, e.g. {@code "LAST 113 TICKETS"}. Always a count or a state —
     * never an adjective.
     */
    public static String headline(MomentumTriggerType trigger, MomentumMetrics m) {
        if (trigger == null || m == null) return null;
        return switch (trigger) {
            case SOLD_OUT -> "SOLD OUT";
            case URGENCY_72H -> m.remaining() > 0
                    ? "LAST " + m.remaining() + " TICKETS"
                    : "FINAL CALL";
            case LAUNCH_PUSH -> "TICKETS LIVE";
            case SLUMP -> m.daysOut() + " DAYS OUT";
        };
    }

    /**
     * Contextual time prose, e.g. {@code "Doors in 64h"} / {@code "On-sale 2 days"} /
     * {@code "Sold out 6 days early"}. Reads from {@code hoursToStart}/{@code hoursSinceOnSale}
     * so it states hours when hours are what matter and days when they are not.
     */
    public static String daysOutLabel(MomentumTriggerType trigger, MomentumMetrics m) {
        if (trigger == null || m == null) return null;
        return switch (trigger) {
            // Sold out with the event still ahead ⇒ it sold out that many days early. That IS
            // daysOut: capacity was reached now, doors are in N days.
            case SOLD_OUT -> m.daysOut() > 0
                    ? "Sold out " + plural(m.daysOut(), "day", "days") + " early"
                    : "Sold out";
            // The whole point of the 72h trigger is that hours, not days, are the unit.
            case URGENCY_72H -> doorsIn(m);
            // Pre-sale framing: how long the tickets have been buyable.
            case LAUNCH_PUSH -> m.hoursSinceOnSale() < 48
                    ? "On-sale " + plural(m.hoursSinceOnSale(), "hour", "hours")
                    : "On-sale " + plural(m.hoursSinceOnSale() / 24, "day", "days");
            case SLUMP -> plural(m.daysOut(), "day", "days") + " out";
        };
    }

    /**
     * Hours are the unit right up to 72: the urgency trigger's entire window is the last 72h,
     * so rounding 64h down to "2 days" would both understate the squeeze and throw away the
     * precision the trigger is built on. Past 72h (this label is reachable generically) days
     * become the readable unit.
     */
    private static String doorsIn(MomentumMetrics m) {
        long h = m.hoursToStart();
        if (h <= 0) return "Doors now";
        if (h <= 72) return "Doors in " + h + "h";
        return "Doors in " + plural(h / 24, "day", "days");
    }

    /**
     * The sentence explaining the trend, e.g. {@code "Selling 5 tickets/day, down 46% on the
     * previous 5 days — at this pace you finish around 74% of capacity, not sold out"}.
     *
     * <p>Every clause is a plain restatement of the snapshot. Velocity and projection clauses
     * are dropped entirely when there is no real spark series to measure them from, and the
     * decline clause is dropped when the series does not actually show a decline.
     */
    public static String pace(MomentumTriggerType trigger, MomentumMetrics m) {
        if (trigger == null || m == null) return null;
        return switch (trigger) {
            case SOLD_OUT -> m.sold() + " of " + m.capacity() + " gone"
                    + (m.daysOut() > 0 ? " with " + plural(m.daysOut(), "day", "days") + " still to sell" : "");
            case URGENCY_72H -> {
                String base = m.remaining() + " of " + m.capacity() + " left, " + doorsIn(m).toLowerCase(Locale.ROOT);
                yield m.hasSpark()
                        ? "Selling " + rate(m) + " — " + base
                        : base;
            }
            case LAUNCH_PUSH -> {
                String base = m.sold() + " sold in the first "
                        + plural(m.hoursSinceOnSale(), "hour", "hours")
                        + ", " + m.sellThroughPct() + "% of the room";
                yield m.hasSpark() ? base + " — " + rate(m) : base;
            }
            case SLUMP -> {
                if (!m.hasSpark()) {
                    yield "Pace has slowed at " + m.sellThroughPct() + "% sold, "
                            + plural(m.daysOut(), "day", "days") + " out";
                }
                String trend = declineClause(m);
                // Projection is only honest with a real velocity behind it.
                yield "Selling " + rate(m) + trend + " — at this pace you finish around "
                        + m.projectedSellThroughPct() + "% of capacity"
                        + (m.projectedSellThroughPct() < 100 ? ", not sold out" : "");
            }
        };
    }

    /**
     * "↓46%"-style clause comparing the trailing half of the real spark against its earlier
     * half. Returns "" when the series is too short to support the comparison, or when the
     * trend is not actually a decline — never asserts a direction the data does not show.
     */
    private static String declineClause(MomentumMetrics m) {
        List<Integer> s = m.spark();
        if (s == null || s.size() < 4) return "";
        int half = s.size() / 2;
        double earlier = mean(s.subList(0, half));
        double later = mean(s.subList(half, s.size()));
        if (earlier <= 0 || later >= earlier) return "";
        int dropPct = (int) Math.round((earlier - later) / earlier * 100.0);
        if (dropPct <= 0) return "";
        return ", down " + dropPct + "% on the previous " + plural(half, "day", "days");
    }

    private static double mean(List<Integer> xs) {
        if (xs.isEmpty()) return 0;
        int sum = 0;
        for (int x : xs) sum += x;
        return sum / (double) xs.size();
    }

    /** Real tickets/day from the spark, rendered without false precision. */
    private static String rate(MomentumMetrics m) {
        double v = m.ticketsPerDay7d();
        String n = v >= 10 || v == Math.rint(v)
                ? String.valueOf(Math.round(v))
                : String.format(Locale.ROOT, "%.1f", v);
        return n + " tickets/day";
    }

    private static String plural(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
