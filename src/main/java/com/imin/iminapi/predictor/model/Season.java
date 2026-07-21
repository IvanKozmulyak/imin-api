package com.imin.iminapi.predictor.model;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Meteorological season of an event, the seasonality dimension of the comparable
 * corpus (spec §6.3/§6.4). Derived from the event date at publish-freeze and stored
 * on {@code event_outcomes.season}. Northern-hemisphere buckets — imin's launch
 * market is European; a hemisphere-aware refinement is a later concern and is noted
 * rather than silently assumed.
 */
public enum Season {
    WINTER, SPRING, SUMMER, AUTUMN;

    /** Season for an instant, resolved in the given zone. Null instant → null. */
    public static Season of(Instant when, ZoneId zone) {
        if (when == null) return null;
        int month = when.atZone(zone == null ? ZoneId.of("UTC") : zone).getMonthValue();
        return switch (month) {
            case 12, 1, 2 -> WINTER;
            case 3, 4, 5 -> SPRING;
            case 6, 7, 8 -> SUMMER;
            default -> AUTUMN; // 9,10,11
        };
    }
}
