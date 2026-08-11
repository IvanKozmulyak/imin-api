package com.imin.iminapi.service.event;

import java.util.Optional;

/**
 * The default {@link Geocoder}: always "no answer".
 *
 * <p>With this bound, {@code events.venue_latitude/longitude} stay NULL forever and
 * the buyer event page keeps exactly the behaviour it had before V80 — address text
 * plus a maps deep link. That is the intended fallback, not a degraded mode: no
 * outbound call is made, no third-party terms are accepted, and nothing in the write
 * path can fail because of geocoding.
 */
public class NoOpGeocoder implements Geocoder {

    @Override
    public Optional<GeoPoint> geocode(String street, String city, String postalCode, String country) {
        return Optional.empty();
    }
}
