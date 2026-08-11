package com.imin.iminapi.dto.publicapi;

/**
 * Venue block on the public event page.
 *
 * <p>{@code latitude}/{@code longitude} (V80) are nullable and always travel as a
 * pair. They are populated by best-effort geocoding on the organizer write path,
 * which is disabled by default — so <b>null is the ordinary case</b>, not a fault.
 * A consumer must treat "no coordinates" as "render the address + maps deep link",
 * exactly as it did before this field existed, and must never substitute a default
 * point.
 */
public record PublicVenueDto(
        String name,
        String street,
        String city,
        String postalCode,
        String country,
        Double latitude,
        Double longitude
) {}
