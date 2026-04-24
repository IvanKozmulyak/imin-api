package com.imin.iminapi.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Times {

    private Times() {}

    /**
     * {@link Instant#now()} truncated to microseconds so the in-memory value matches
     * what Postgres TIMESTAMP columns actually persist. Needed for If-Match comparisons
     * and any other round-trip where the client echoes back a timestamp we sent out.
     */
    public static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
