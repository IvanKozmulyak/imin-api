package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.service.QuietHours;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class QuietHoursTest {

    private final QuietHours quietHours = new QuietHours();

    private Instant kyivAt(int hour) {
        return ZonedDateTime.of(2026, 7, 11, hour, 30, 0, 0, ZoneId.of("Europe/Kyiv")).toInstant();
    }

    @Test
    void insideQuietWindowLateNight() {
        assertThat(quietHours.isEmailQuiet("Europe/Kyiv", kyivAt(23))).isTrue(); // 23:30
    }

    @Test
    void insideQuietWindowEarlyMorning() {
        assertThat(quietHours.isEmailQuiet("Europe/Kyiv", kyivAt(7))).isTrue(); // 07:30
    }

    @Test
    void outsideQuietWindowMidday() {
        assertThat(quietHours.isEmailQuiet("Europe/Kyiv", kyivAt(14))).isFalse(); // 14:30
    }

    @Test
    void boundaryNineAmIsAllowed() {
        Instant nineExactly = ZonedDateTime.of(2026, 7, 11, 9, 0, 0, 0,
            ZoneId.of("Europe/Kyiv")).toInstant();
        assertThat(quietHours.isEmailQuiet("Europe/Kyiv", nineExactly)).isFalse();
    }

    @Test
    void blankTimezoneFallsBackToUtc() {
        // 23:30 UTC is quiet regardless
        Instant utc2330 = ZonedDateTime.of(2026, 7, 11, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant();
        assertThat(quietHours.isEmailQuiet(null, utc2330)).isTrue();
    }

    /** Auto stock alerts: the first allowed instant in the org's zone, including where UTC and Paris disagree on the day. */
    @ParameterizedTest(name = "{0} in {1} -> {2}")
    @CsvSource({
            // 22:30Z on 6 Nov is 23:30 on 6 Nov in Paris: quiet, so the next 09:00 Paris on 7 Nov.
            "2026-11-06T22:30:00Z, Europe/Paris, 2026-11-07T08:00:00Z",
            // 23:30Z on 6 Nov is already 00:30 on 7 Nov in Paris: 09:00 the same Paris day, not 8 Nov.
            "2026-11-06T23:30:00Z, Europe/Paris, 2026-11-07T08:00:00Z",
            // 07:30 Paris is quiet: 09:00 that morning.
            "2026-11-07T06:30:00Z, Europe/Paris, 2026-11-07T08:00:00Z",
            // 14:30 Paris is allowed: unchanged.
            "2026-11-07T13:30:00Z, Europe/Paris, 2026-11-07T13:30:00Z",
            // Exactly 09:00 Paris may send; exactly 22:00 is quiet until the next morning.
            "2026-11-07T08:00:00Z, Europe/Paris, 2026-11-07T08:00:00Z",
            "2026-11-07T21:00:00Z, Europe/Paris, 2026-11-08T08:00:00Z",
            // A blank zone falls back to UTC.
            "2026-11-06T22:30:00Z, '', 2026-11-07T09:00:00Z",
    })
    void nextEmailAllowed_isTheFirstInstantOutsideTheOrgsQuietHours(String from, String zone, String expected) {
        assertThat(quietHours.nextEmailAllowed(zone, Instant.parse(from))).isEqualTo(Instant.parse(expected));
    }
}
