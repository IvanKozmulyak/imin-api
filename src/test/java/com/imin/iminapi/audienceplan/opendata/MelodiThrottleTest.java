package com.imin.iminapi.audienceplan.opendata;

import com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.MutableClock;
import com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.RecordingSleeper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class MelodiThrottleTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-27T08:00:00Z"));
    private final RecordingSleeper sleeper = new RecordingSleeper();
    private final MelodiThrottle throttle = new MelodiThrottle(clock, sleeper);

    static Stream<Arguments> calls() {
        // delay before a second call (null = a single call), the waits the throttle makes
        return Stream.of(
                arguments(null, List.of()),
                arguments(Duration.ofMillis(500), List.of(Duration.ofMillis(1500))),
                arguments(MelodiThrottle.MIN_INTERVAL.plusSeconds(8), List.of()));
    }

    @ParameterizedTest
    @MethodSource("calls")
    void aCallWaitsOnlyTheRemainderOfTheInterval(Duration delay, List<Duration> expectedSleeps) throws Exception {
        throttle.acquire();
        if (delay != null) {
            clock.advance(delay);
            throttle.acquire();
        }

        assertThat(sleeper.sleeps).containsExactlyElementsOf(expectedSleeps);
    }
}
