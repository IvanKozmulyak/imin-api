package com.imin.iminapi.audienceplan.opendata;

import com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.MutableClock;
import com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.RecordingSleeper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MelodiThrottleTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-27T08:00:00Z"));
    private final RecordingSleeper sleeper = new RecordingSleeper();
    private final MelodiThrottle throttle = new MelodiThrottle(clock, sleeper);

    @Test
    void theFirstCallDoesNotWait() throws Exception {
        throttle.acquire();

        assertThat(sleeper.sleeps).isEmpty();
    }

    @Test
    void aSecondCallInsideTheIntervalWaitsTheRemainder() throws Exception {
        throttle.acquire();
        clock.advance(Duration.ofMillis(500));

        throttle.acquire();

        assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(1500));
    }

    @Test
    void aCallAfterTheIntervalDoesNotWait() throws Exception {
        throttle.acquire();
        clock.advance(MelodiThrottle.MIN_INTERVAL.plusSeconds(8));

        throttle.acquire();

        assertThat(sleeper.sleeps).isEmpty();
    }

    @Test
    void theIntervalKeepsCallsAtThirtyPerMinute() {
        assertThat(Duration.ofMinutes(1).dividedBy(MelodiThrottle.MIN_INTERVAL)).isEqualTo(30);
    }
}
