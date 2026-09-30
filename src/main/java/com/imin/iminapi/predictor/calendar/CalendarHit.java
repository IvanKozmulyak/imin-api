package com.imin.iminapi.predictor.calendar;

import java.time.LocalDate;

/**
 * A calendar fact for a place and dates. {@code region} "" = the whole country. {@code origin} is {@code synced} (from reference_calendar)
 * or {@code fallback} (the static table; no source URL). {@code approximate} marks computed Hijri dates.
 */
public record CalendarHit(LocalDate date, LocalDate endDate, String kind, String name, String region, String sourceUrl,
                          boolean approximate, String origin) {

    public static final String SYNCED = "synced";
    public static final String FALLBACK = "fallback";
}
