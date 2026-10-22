package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.service.MomentumMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the spark series ({@link MomentumMetrics#dailySpark}) and its tickets/day mean.
 * No Spring context — both are pure functions of real inputs.
 *
 * <p>The load-bearing test here is {@link #sparkCanRepresentADecliningSeries()}: the FE used to
 * synthesize the curve from a single scalar, which rose BY CONSTRUCTION and could never draw the
 * decline the {@code slump} trigger exists to signal.
 */
class MomentumSparkTest {

    private static final Instant NOW = Instant.parse("2026-07-16T09:00:00Z");

    /** Ticket timestamps: {@code perDay} oldest → newest, stamped at midday UTC. */
    private static List<Instant> ticketsAt(int... perDay) {
        List<Instant> out = new ArrayList<>();
        Instant midday = Instant.parse("2026-07-16T12:00:00Z");
        for (int i = 0; i < perDay.length; i++) {
            Instant at = midday.minus(Duration.ofDays(perDay.length - 1L - i));
            for (int n = 0; n < perDay[i]; n++) out.add(at);
        }
        return out;
    }

    // ---------- the spark series ----------

    @Test
    void sparkBucketsRealTicketsIntoOnePointPerDay() {
        // On-sale 3 days ago ⇒ the window is those 3 days + today.
        List<Instant> sold = ticketsAt(3, 0, 5, 1);
        List<Integer> spark = MomentumMetrics.dailySpark(sold, NOW.minus(Duration.ofDays(3)), NOW);

        // Exactly the shape the tickets describe — including the genuine zero day, which a
        // curve interpolated from a scalar would have smoothed away.
        assertThat(spark).containsExactly(3, 0, 5, 1);
    }

    @Test
    void sparkCanRepresentADecliningSeries() {
        // The whole point. A slump event: sales cooling day over day.
        List<Instant> sold = ticketsAt(9, 14, 11, 16, 13, 10, 8, 6, 5, 5);
        List<Integer> spark = MomentumMetrics.dailySpark(sold, NOW.minus(Duration.ofDays(30)), NOW);

        assertThat(spark).containsExactly(9, 14, 11, 16, 13, 10, 8, 6, 5, 5);
        // Not merely stored — the series actually ENDS below where it started, which the
        // old scalar-synthesized curve was structurally incapable of doing.
        assertThat(spark.get(spark.size() - 1)).isLessThan(spark.get(0));
        assertThat(spark.get(spark.size() - 1)).isLessThan(spark.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    @Test
    void sparkIsEmptyWhenThereAreNoSoldTickets() {
        // No data ⇒ no series. Never a fabricated curve.
        assertThat(MomentumMetrics.dailySpark(List.of(), NOW.minus(Duration.ofDays(30)), NOW)).isEmpty();
        assertThat(MomentumMetrics.dailySpark(null, NOW.minus(Duration.ofDays(30)), NOW)).isEmpty();
    }

    @Test
    void sparkIsCappedAtSparkDaysAndNeverStartsBeforeOnSale() {
        // 20 days of sales, but the window is SPARK_DAYS long.
        int[] twenty = new int[20];
        java.util.Arrays.fill(twenty, 2);
        List<Integer> capped = MomentumMetrics.dailySpark(
                ticketsAt(twenty), NOW.minus(Duration.ofDays(60)), NOW);
        assertThat(capped).hasSize(MomentumMetrics.SPARK_DAYS);

        // On-sale 3 days ago ⇒ 3 points (today inclusive), not 7 leading zeros of noise
        // from before tickets could be bought at all.
        List<Integer> clamped = MomentumMetrics.dailySpark(
                ticketsAt(4, 5, 6), NOW.minus(Duration.ofDays(2)), NOW);
        assertThat(clamped).hasSize(3).containsExactly(4, 5, 6);
    }

    @Test
    void ticketsPerDayIsTheRealTrailingSevenDayMeanOfTheSpark() {
        MomentumMetrics m = MomentumMetrics.compute(
                70, 100, 3, NOW.minus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(20)), NOW,
                ticketsAt(9, 14, 11, 16, 13, 10, 8, 6, 5, 5));
        // Trailing 7 of [.., 16,13,10,8,6,5,5] = 63/7 = 9.0 tickets/day.
        assertThat(m.ticketsPerDay7d()).isEqualTo(9.0);
        // velocity7d stays ORDERS/day (3/7) — a different unit, deliberately untouched.
        assertThat(m.velocity7d()).isEqualTo(3 / 7.0);
    }

    @Test
    void snapshotJsonCarriesTheSeriesSoItSurvivesToTheCard() {
        String json = MomentumMetrics.compute(
                70, 100, 3, NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(20)), NOW,
                ticketsAt(4, 5, 6)).toJson();
        assertThat(json)
                .contains("\"spark\":[4,5,6]")
                .contains("\"sparkStartDate\":\"2026-07-14\"")
                .contains("\"ticketsPerDay7d\":5.00")
                .contains("\"hoursToStart\":");
    }
}
