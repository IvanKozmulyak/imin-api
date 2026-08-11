package com.imin.iminapi.service.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Venue geocoding config (V80). Bound to {@code imin.geocoding.*}.
 *
 * <p><b>Disabled by default.</b> With {@code enabled=false} the {@link NoOpGeocoder}
 * is bound, nothing leaves the process, and venue coordinates stay NULL — the buyer
 * page's pre-V80 deep-link behaviour. Turning it on opts into Nominatim (OpenStreetMap),
 * which needs no key but does impose terms: max ~1 request/second and an identifying
 * User-Agent. Both are honoured by {@link NominatimGeocoder}; do not raise the rate
 * without reading https://operations.osmfoundation.org/policies/nominatim/ first.
 */
@ConfigurationProperties(prefix = "imin.geocoding")
public class GeocodingProperties {

    /** {@code IMIN_GEOCODING_ENABLED} — false binds NoOpGeocoder (default). */
    private boolean enabled = false;

    /** {@code IMIN_GEOCODING_BASE_URL} — Nominatim-compatible search endpoint. */
    private String baseUrl = "https://nominatim.openstreetmap.org/search";

    /**
     * {@code IMIN_GEOCODING_USER_AGENT} — Nominatim REQUIRES a real identifying
     * User-Agent with contact info; requests without one are blocked.
     */
    private String userAgent = "imin-api/1.0 (+https://imin.wtf; ops@imin.wtf)";

    /** {@code IMIN_GEOCODING_TIMEOUT_SECONDS} — per-request ceiling. */
    private int timeoutSeconds = 5;

    /** {@code IMIN_GEOCODING_MIN_INTERVAL_MILLIS} — client-side throttle, Nominatim's 1 req/s policy. */
    private long minIntervalMillis = 1100;

    /**
     * {@code IMIN_GEOCODING_MAX_THROTTLE_WAIT_MILLIS} — safety valve on the throttle.
     *
     * <p>When the queue of reserved slots pushes a caller's turn further out than this, that
     * lookup is SKIPPED rather than fired early: no coordinates is a supported state everywhere
     * downstream, an OSM IP block is not. Generous by design — the work runs on a
     * single-threaded executor, so in normal operation a caller waits at most one interval and
     * this never trips.
     *
     * <p>It is a ceiling on the BACKLOG, with no implicit floor: setting it below
     * {@link #minIntervalMillis} means every queued lookup is skipped, which is a legitimate
     * (if drastic) way to say "only geocode when the provider is immediately free".
     */
    private long maxThrottleWaitMillis = 60_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public long getMinIntervalMillis() { return minIntervalMillis; }
    public void setMinIntervalMillis(long minIntervalMillis) { this.minIntervalMillis = minIntervalMillis; }

    public long getMaxThrottleWaitMillis() { return maxThrottleWaitMillis; }
    public void setMaxThrottleWaitMillis(long maxThrottleWaitMillis) {
        this.maxThrottleWaitMillis = maxThrottleWaitMillis;
    }
}
