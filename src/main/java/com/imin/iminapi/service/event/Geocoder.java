package com.imin.iminapi.service.event;

import java.util.Optional;

/**
 * Address → point lookup for venue coordinates (V80).
 *
 * <p>The seam exists so the buyer page's map can never depend on a provider being
 * reachable, licensed, or configured. Every implementation returns
 * {@link Optional#empty()} rather than throwing or guessing: an empty result means
 * "no coordinates", which the whole stack already handles (nullable columns →
 * nullable {@code PublicVenueDto.latitude/longitude} → maps deep link on the FE).
 *
 * <p>Default binding is {@link NoOpGeocoder}. Set {@code IMIN_GEOCODING_ENABLED=true}
 * to bind {@link NominatimGeocoder} instead — see {@link GeocodingProperties}.
 */
public interface Geocoder {

    /** WGS84 degrees. Always produced as a pair; never partially populated. */
    record GeoPoint(double latitude, double longitude) {}

    /**
     * Resolves a venue address. Implementations MUST NOT throw and MUST NOT
     * fabricate — an unknown address yields {@link Optional#empty()}.
     */
    Optional<GeoPoint> geocode(String street, String city, String postalCode, String country);
}
