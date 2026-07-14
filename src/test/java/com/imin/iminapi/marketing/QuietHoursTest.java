package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.service.QuietHours;
import org.junit.jupiter.api.Test;

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
}
