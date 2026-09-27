package com.imin.iminapi.audienceplan.opendata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Real answers recorded from the sources on 2026-09-27 (see src/main/resources/audienceplan/open-data/README.md). */
final class OpenDataFixtures {

    static final OpenDataCity METZ = new OpenDataCity("metz", "Metz", "FR", "57463", "Moselle");
    static final OpenDataCity NANCY = new OpenDataCity("nancy", "Nancy", "FR", "54395", "Meurthe-et-Moselle");
    static final OpenDataCity THIONVILLE = new OpenDataCity("thionville", "Thionville", "FR", "57672", "Moselle");

    private OpenDataFixtures() {}

    static byte[] bytes(String name) {
        try (InputStream in = OpenDataFixtures.class.getClassLoader()
                .getResourceAsStream("audienceplan/open-data/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    /** Records every requested sleep instead of sleeping. */
    static final class RecordingSleeper implements Sleeper {
        final List<Duration> sleeps = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
        }
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
