package com.imin.iminapi.predictor.rules;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Maps event starts to the night they belong to: a start before 06:00 local belongs to the previous night. */
public final class NightDates {

    public static final int NIGHT_ROLLOVER_HOUR = 6;

    private NightDates() {}

    public static LocalDate nightOf(Instant startsAt, ZoneId zone) {
        ZonedDateTime local = startsAt.atZone(zone);
        LocalDate date = local.toLocalDate();
        return local.getHour() < NIGHT_ROLLOVER_HOUR ? date.minusDays(1) : date;
    }

    /** First instant of the night, local 06:00. */
    public static Instant nightStart(LocalDate night, ZoneId zone) {
        return night.atTime(LocalTime.of(NIGHT_ROLLOVER_HOUR, 0)).atZone(zone).toInstant();
    }
}
