package com.imin.iminapi.checkoutreminder;

import com.imin.iminapi.checkoutreminder.service.CheckoutReminderSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A Paris buyer is never emailed at night, and the email names the night's own date. */
class CheckoutReminderScheduleTest {

    private static final Duration HOUR = Duration.ofMinutes(60);

    @ParameterizedTest(name = "started {0} → due {1}")
    @CsvSource({
            // 23:30 Paris on Sat 7 Nov (22:30Z): +60 min is 00:30 Paris on the 8th, quiet → 09:00 Paris = 08:00Z.
            "2026-11-07T22:30:00Z, 2026-11-08T08:00:00Z",
            // 21:30 Paris (20:30Z): +60 min is 22:30 Paris, quiet → next day 09:00 Paris.
            "2026-11-07T20:30:00Z, 2026-11-08T08:00:00Z",
            // 06:00 Paris (05:00Z): +60 min is 07:00 Paris, quiet → same day 09:00 Paris.
            "2026-11-07T05:00:00Z, 2026-11-07T08:00:00Z",
            // 08:00 Paris (07:00Z): +60 min is 09:00 sharp, which may send.
            "2026-11-07T07:00:00Z, 2026-11-07T08:00:00Z",
            // 20:59 Paris (19:59Z): +60 min is 21:59, still allowed.
            "2026-11-07T19:59:00Z, 2026-11-07T20:59:00Z"
    })
    void dueTimeAndEmailDateUseTheRightZone(Instant started, Instant due) {
        assertThat(CheckoutReminderSchedule.dueAt(started, "Europe/Paris", HOUR)).isEqualTo(due);
    }

    @Test
    void theEmailDateIsTheEventZonesDay() {
        // 23:30Z on Saturday 7 Nov is 00:30 on Sunday 8 Nov in Paris.
        Instant start = Instant.parse("2026-11-07T23:30:00Z");

        assertThat(CheckoutReminderSchedule.eventWhen(start, "Europe/Paris", "en")).isEqualTo("Sun 8 Nov · 00:30");
        assertThat(CheckoutReminderSchedule.eventWhen(start, "Europe/Paris", "fr")).isEqualTo("dim. 8 nov. · 00:30");
    }

    @ParameterizedTest(name = "doors in {0} min → too close {1}")
    @CsvSource({"179, true", "180, false", "181, false"})
    void theDoorsLimitIsThreeHours(long minutesToDoors, boolean tooClose) {
        Instant now = Instant.parse("2026-11-07T12:00:00Z");
        Instant doors = now.plus(Duration.ofMinutes(minutesToDoors));

        assertThat(CheckoutReminderSchedule.tooCloseToDoors(doors, now, Duration.ofHours(3))).isEqualTo(tooClose);
    }

    @ParameterizedTest(name = "started {0} min ago → too late {1}")
    @CsvSource({"1440, false", "1441, true"})
    void theAgeLimitIsTwentyFourHours(long minutesAgo, boolean tooLate) {
        Instant now = Instant.parse("2026-11-07T12:00:00Z");

        assertThat(CheckoutReminderSchedule.tooLate(now.minus(Duration.ofMinutes(minutesAgo)), now,
                Duration.ofHours(24))).isEqualTo(tooLate);
    }
}
