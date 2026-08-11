package com.imin.iminapi.service.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link Geocoder} backed by Nominatim (OpenStreetMap) — free, no API key, street-level.
 *
 * <p>Only bound when {@code imin.geocoding.enabled=true}. Nominatim's usage policy caps
 * anonymous clients at roughly one request per second and requires an identifying
 * User-Agent, so this client serialises calls behind {@code minIntervalMillis} and always
 * sends {@code GeocodingProperties.userAgent}. Venue writes are rare enough that the
 * throttle is invisible (the caller is already off the request thread, on the
 * single-threaded {@code venueGeocodingExecutor}).
 *
 * <p>The rate guard is per-JVM and holds for any burst size within one — see
 * {@link #throttle()} for what it does and does not promise across replicas.
 *
 * <p>Failure is never propagated: a timeout, a 4xx/5xx, a blocked User-Agent, or an
 * address the provider doesn't know all return {@link Optional#empty()}, which leaves the
 * coordinates NULL and the buyer page on its deep-link fallback.
 */
public class NominatimGeocoder implements Geocoder {

    private static final Logger log = LoggerFactory.getLogger(NominatimGeocoder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GeocodingProperties props;
    private final HttpClient http;
    /** Epoch millis of the next permitted call — the 1 req/s policy guard. */
    private final AtomicLong nextCallAtMillis = new AtomicLong(0);

    public NominatimGeocoder(GeocodingProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())))
                .build();
    }

    @Override
    public Optional<GeoPoint> geocode(String street, String city, String postalCode, String country) {
        String query = buildQuery(street, city, postalCode, country);
        // A bare country (or nothing at all) would resolve to a country centroid — a pin
        // in a random field. Require at least a city before asking.
        if (query == null) return Optional.empty();

        try {
            // Skipping beats firing early: no coordinates is a supported state, an IP block is not.
            if (!throttle()) return Optional.empty();
            String url = props.getBaseUrl()
                    + "?format=jsonv2&limit=1&addressdetails=0&q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", props.getUserAgent())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())))
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.debug("[geocode] HTTP {} for \"{}\" — no coordinates", res.statusCode(), query);
                return Optional.empty();
            }
            JsonNode arr = MAPPER.readTree(res.body());
            if (!arr.isArray() || arr.isEmpty()) return Optional.empty();
            JsonNode first = arr.get(0);
            if (!first.hasNonNull("lat") || !first.hasNonNull("lon")) return Optional.empty();
            double lat = Double.parseDouble(first.get("lat").asText());
            double lon = Double.parseDouble(first.get("lon").asText());
            if (!inRange(lat, lon)) return Optional.empty();
            return Optional.of(new GeoPoint(lat, lon));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("[geocode] lookup failed for \"{}\": {} — no coordinates", query, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Free-form query from the address parts. Returns null when there is no city —
     * street/postcode alone are ambiguous worldwide and a country alone is a centroid.
     */
    static String buildQuery(String street, String city, String postalCode, String country) {
        if (isBlank(city)) return null;
        List<String> parts = new ArrayList<>(4);
        if (!isBlank(street)) parts.add(street.trim());
        parts.add(city.trim());
        if (!isBlank(postalCode)) parts.add(postalCode.trim());
        if (!isBlank(country)) parts.add(country.trim());
        return String.join(", ", parts);
    }

    static boolean inRange(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /**
     * Waits out this caller's reserved slot so calls leave at most one per
     * {@code minIntervalMillis}. Returns false when the slot is further out than
     * {@code maxThrottleWaitMillis}, in which case the caller must NOT call the provider.
     *
     * <p>The reservation ({@code getAndUpdate}) hands caller <i>k</i> of a burst the instant
     * {@code T + (k-1)·interval} and is correct for any k. What used to be wrong was the sleep:
     * it was capped at {@code interval * 4}, so from the 6th concurrent caller on, everyone woke
     * early and fired at once — the exact burst the throttle exists to prevent, answered by
     * OpenStreetMap with an IP block. The wait now honours the slot in full.
     *
     * <p>Giving up (rather than firing early) is the only safe overflow behaviour: a skipped
     * geocode leaves the coordinates NULL, which is a supported state everywhere downstream,
     * whereas an early call risks the block that takes the whole feature down. The reserved
     * slot is deliberately NOT released — releasing it would let a later caller move up.
     *
     * <p><b>Per-JVM, not per-cluster.</b> {@code nextCallAtMillis} is process state, so N
     * replicas geocoding at once emit up to N req/s between them. Two things keep that
     * theoretical rather than real today: geocoding is off by default, and the work is bound
     * to a single-threaded executor per replica (see {@code AsyncConfig.venueGeocodingExecutor}),
     * so a replica's ceiling is 1/interval and the fleet's is N/interval on a workload measured
     * in venue edits per day. If this is ever turned on with a large fleet, or pointed at a
     * self-hosted Nominatim with a tighter budget, the reservation has to move to shared state
     * (a Postgres advisory lock or the existing ShedLock table) — a comment cannot enforce it.
     */
    private boolean throttle() throws InterruptedException {
        long interval = Math.max(0, props.getMinIntervalMillis());
        if (interval == 0) return true;
        long now = System.currentTimeMillis();
        long slot = nextCallAtMillis.getAndUpdate(prev -> Math.max(prev, now) + interval);
        long waitMs = Math.max(slot, now) - now;
        if (waitMs <= 0) return true;
        long maxWait = props.getMaxThrottleWaitMillis();
        if (waitMs > maxWait) {
            log.debug("[geocode] throttle backlog {}ms exceeds the {}ms ceiling — skipping this lookup",
                    waitMs, maxWait);
            return false;
        }
        Thread.sleep(waitMs);
        return true;
    }
}
