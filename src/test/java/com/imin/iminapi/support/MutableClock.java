package com.imin.iminapi.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** UTC clock that follows system time until a test pins it; reset after every test. */
public final class MutableClock extends Clock {

    private Instant pinned;

    public synchronized void setInstant(Instant instant) {
        this.pinned = instant;
    }

    /** Moves a pinned clock; an unpinned one is pinned at system-now plus {@code d}. */
    public synchronized void advance(Duration d) {
        this.pinned = (pinned == null ? Instant.now() : pinned).plus(d);
    }

    public synchronized void reset() {
        this.pinned = null;
    }

    @Override
    public synchronized Instant instant() {
        return pinned == null ? Instant.now() : pinned;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock source = this;
        return new Clock() {
            @Override public Instant instant() { return source.instant(); }
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId other) { return source.withZone(other); }
        };
    }
}
