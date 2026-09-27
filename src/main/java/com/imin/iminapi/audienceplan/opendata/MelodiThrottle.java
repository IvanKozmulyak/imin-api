package com.imin.iminapi.audienceplan.opendata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Spaces INSEE Melodi calls to its anonymous limit (30 calls/min); per JVM, not across replicas. */
public class MelodiThrottle {

    static final Duration MIN_INTERVAL = Duration.ofSeconds(2);

    private final Clock clock;
    private final Sleeper sleeper;
    private Instant nextSlot;

    public MelodiThrottle(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Reserves the next slot and waits for it. */
    public void acquire() throws InterruptedException {
        Duration wait;
        synchronized (this) {
            Instant now = clock.instant();
            Instant slot = nextSlot == null || nextSlot.isBefore(now) ? now : nextSlot;
            nextSlot = slot.plus(MIN_INTERVAL);
            wait = Duration.between(now, slot);
        }
        if (!wait.isZero() && !wait.isNegative()) {
            sleeper.sleep(wait);
        }
    }
}
