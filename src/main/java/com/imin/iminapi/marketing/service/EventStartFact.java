package com.imin.iminapi.marketing.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The event start as local weekday, date and time in the event's zone, for AI prompts. */
public final class EventStartFact {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH);

    private EventStartFact() {}

    /** "Saturday 31 October 2026, 00:30 (Europe/Paris)"; an invalid zone falls back to UTC; null start gives null. */
    public static String describe(Instant startsAt, String zoneId) {
        if (startsAt == null) return null;
        ZoneId zone;
        try {
            zone = zoneId == null || zoneId.isBlank() ? ZoneOffset.UTC : ZoneId.of(zoneId.trim());
        } catch (RuntimeException e) {
            zone = ZoneOffset.UTC;
        }
        String label = zone.equals(ZoneOffset.UTC) ? "UTC" : zone.getId();
        ZonedDateTime local = startsAt.atZone(zone);
        return FORMAT.format(local) + " (" + label + ")";
    }
}
