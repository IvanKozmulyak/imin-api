package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.MomentumFacts;
import com.imin.iminapi.marketing.service.MomentumMetrics;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The card's rate, projection and decline restate only what the frozen series shows. */
class MomentumFactsTest {

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

    static Stream<Arguments> cases() {
        return Stream.of(
                // Earlier half mean 12.6, later 6.8: a real 46% drop over 5 days; 60 + 9/day × 20 days = 240 of 400.
                Arguments.of("declining series", 60, 400, 30, ticketsAt(9, 14, 11, 16, 13, 10, 8, 6, 5, 5),
                        9.0, 60, 46, 5),
                Arguments.of("rising series", 45, 400, 5, ticketsAt(2, 3, 4, 8, 12, 16), 7.5, 49, null, null),
                Arguments.of("fewer than 4 points", 15, 400, 2, ticketsAt(9, 5, 1), 5.0, 29, null, null),
                Arguments.of("no series", 60, 400, 30, List.<Instant>of(), null, null, null, null),
                Arguments.of("no capacity", 6, 0, 2, ticketsAt(2, 2, 2), 2.0, null, null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void restatesOnlyWhatTheSnapshotShows(String label, int sold, int capacity, int onSaleDaysAgo,
                                          List<Instant> soldAt, Double rate, Integer projected,
                                          Integer declinePct, Integer declineDays) {
        MomentumMetrics m = MomentumMetrics.compute(sold, capacity, 3, NOW.minus(Duration.ofDays(onSaleDaysAgo)),
                NOW.plus(Duration.ofDays(20)), NOW, soldAt);

        MomentumFacts facts = MomentumFacts.from(m, null);

        assertThat(facts.ticketsPerDay7d()).isEqualTo(rate);
        assertThat(facts.projectedSellThroughPct()).isEqualTo(projected);
        assertThat(facts.declinePct()).isEqualTo(declinePct);
        assertThat(facts.declineDays()).isEqualTo(declineDays);
    }
}
