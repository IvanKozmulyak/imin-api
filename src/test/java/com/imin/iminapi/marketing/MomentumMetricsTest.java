package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.service.MomentumMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MomentumMetricsTest {

    @Test
    void sellThroughIsSoldOverCapacity() {
        Instant now = Instant.parse("2026-07-11T12:00:00Z");
        MomentumMetrics m = MomentumMetrics.compute(
                20, 100,
                14,                                  // orders in last 7d
                now.minus(Duration.ofDays(10)),      // onSaleAt
                now.plus(Duration.ofDays(20)),       // startsAt
                now);
        assertThat(m.sellThroughPct()).isEqualTo(20);
    }

    @Test
    void velocity7dIsOrdersPerDay() {
        Instant now = Instant.parse("2026-07-11T12:00:00Z");
        MomentumMetrics m = MomentumMetrics.compute(
                20, 100, 14,
                now.minus(Duration.ofDays(10)),
                now.plus(Duration.ofDays(20)),
                now);
        assertThat(m.velocity7d()).isCloseTo(2.0, within(0.001)); // 14/7
    }

    @Test
    void requiredVelocityToTargetIsRemainingOverDaysOut() {
        Instant now = Instant.parse("2026-07-11T12:00:00Z");
        MomentumMetrics m = MomentumMetrics.compute(
                20, 100, 14,
                now.minus(Duration.ofDays(10)),
                now.plus(Duration.ofDays(20)), // 20 days out
                now);
        // to reach 50% (=50 sold) from 20, need 30 more over 20 days = 1.5/day
        assertThat(m.requiredVelocityToTarget(50)).isCloseTo(1.5, within(0.001));
    }

    @Test
    void daysOutAndHoursSinceOnSaleAreComputed() {
        Instant now = Instant.parse("2026-07-11T12:00:00Z");
        MomentumMetrics m = MomentumMetrics.compute(
                5, 100, 5,
                now.minus(Duration.ofHours(50)),  // 50h since on-sale
                now.plus(Duration.ofDays(3)),     // 3 days out
                now);
        assertThat(m.daysOut()).isEqualTo(3);
        assertThat(m.hoursSinceOnSale()).isEqualTo(50);
    }

    @Test
    void zeroCapacityDoesNotDivideByZero() {
        Instant now = Instant.parse("2026-07-11T12:00:00Z");
        MomentumMetrics m = MomentumMetrics.compute(
                0, 0, 0, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)), now);
        assertThat(m.sellThroughPct()).isEqualTo(0);
    }

    @Test
    void sellThroughReflectsTicketsSoldNotOrderCount() {
        // Guardrail against the order-vs-ticket bug: `sold` is TICKETS sold, not orders.
        // One order that bought 4 tickets must read as sold=4 (not 1). The evaluator
        // feeds `sold = tiers.sumSoldByEventId(eventId)` (Task 6), so here we assert the
        // pure record honours the ticket count it is given.
        Instant now = Instant.parse("2026-07-11T12:00:00Z");
        MomentumMetrics m = MomentumMetrics.compute(
                /*sold (tickets)*/ 4, /*capacity*/ 100,
                /*orders last 7d*/ 1,                 // one order …
                now.minus(Duration.ofDays(2)),
                now.plus(Duration.ofDays(20)),
                now);
        assertThat(m.sold()).isEqualTo(4);            // … that sold 4 tickets
        assertThat(m.sellThroughPct()).isEqualTo(4);  // 4/100, not 1/100
    }

    @Test
    void toJsonHasExactlyTheKeysTheFrontendSnapshotDeclares() {
        // Locks the metrics_snapshot JSON contract to the FE MomentumMetricsSnapshot
        // (webapp types.ts, Task 9). draft_payload / metrics_snapshot are opaque `string`
        // in MomentumSuggestionDto, so api:sync does NOT cover them. This BE key-presence
        // check + its FE reciprocal (Momentum.test.tsx "metrics_snapshot JSON contract",
        // Task 10 — parses a fixture's metricsSnapshot and asserts every field is a number)
        // together pin the JSON shape on both ends: a rename on either side fails one test.
        Instant now = Instant.parse("2026-07-11T12:00:00Z");
        String json = MomentumMetrics.compute(
                20, 100, 14,
                now.minus(Duration.ofDays(10)),
                now.plus(Duration.ofDays(20)),
                now).toJson();
        // Every field the FE MomentumMetricsSnapshot declares must be present.
        assertThat(json)
                .contains("\"sold\":")
                .contains("\"capacity\":")
                .contains("\"sellThroughPct\":")
                .contains("\"daysOut\":")
                .contains("\"hoursSinceOnSale\":")
                .contains("\"velocity7d\":");
    }
}
