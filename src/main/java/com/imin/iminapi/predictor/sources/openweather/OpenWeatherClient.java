package com.imin.iminapi.predictor.sources.openweather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherDay.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenWeather free plan: the 5-day / 3-hour forecast and direct geocoding. The key rides in the
 * {@code appid} query parameter, so no request URL and no exception message is ever logged.
 */
public class OpenWeatherClient {

    private static final Logger log = LoggerFactory.getLogger(OpenWeatherClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Point(double lat, double lon) {}

    public enum GeocodeStatus { FOUND, NOT_FOUND, FAILED }

    /** {@code point} is non-null exactly when {@code status} is FOUND. */
    public record GeocodeResult(GeocodeStatus status, Point point) {
        public static final GeocodeResult NOT_FOUND = new GeocodeResult(GeocodeStatus.NOT_FOUND, null);
        public static final GeocodeResult FAILED = new GeocodeResult(GeocodeStatus.FAILED, null);

        public static GeocodeResult found(Point p) {
            return new GeocodeResult(GeocodeStatus.FOUND, p);
        }
    }

    private final RestClient http;
    private final OpenWeatherProperties props;
    private final Clock clock;
    private final long minIntervalMillis;
    private final long maxWaitMillis;
    private final AtomicLong nextCallAtMillis = new AtomicLong();

    public OpenWeatherClient(RestClient.Builder builder, OpenWeatherProperties props, Clock clock,
                             long minIntervalMillis, long maxWaitMillis) {
        this.http = builder.build();
        this.props = props;
        this.clock = clock;
        this.minIntervalMillis = minIntervalMillis;
        this.maxWaitMillis = maxWaitMillis;
    }

    /** The 3-hour steps for a point, or empty on any failure, throttle skip or unusable body. */
    public Optional<List<Step>> forecast(double lat, double lon) {
        URI uri = URI.create(props.getBaseUrl() + "/data/2.5/forecast?lat=" + plain(lat) + "&lon=" + plain(lon)
                + "&units=metric&appid=" + enc(props.getApiKey()));
        JsonNode root = get(uri, "forecast");
        if (root == null || !root.path("list").isArray()) return Optional.empty();
        List<Step> steps = new ArrayList<>();
        for (JsonNode item : root.path("list")) {
            JsonNode dt = item.path("dt");
            if (!dt.isNumber()) continue;
            steps.add(new Step(Instant.ofEpochSecond(dt.asLong()), number(item.path("main").path("temp")),
                    number(item.path("pop"))));
        }
        return Optional.of(steps);
    }

    /**
     * First match for a city, checked against the venue's ISO country when one is given. A 200
     * with no match (or a different country) is NOT_FOUND; anything else that fails is FAILED.
     */
    public GeocodeResult geocode(String city, String countryCode) {
        if (city == null) return GeocodeResult.NOT_FOUND;
        String name = city.replace(",", " ").strip();
        if (name.isEmpty()) return GeocodeResult.NOT_FOUND;
        String cc = iso2(countryCode);
        URI uri = URI.create(props.getBaseUrl() + "/geo/1.0/direct?q=" + enc(name) + (cc == null ? "" : "," + enc(cc))
                + "&limit=1&appid=" + enc(props.getApiKey()));
        JsonNode root = get(uri, "geocode");
        if (root == null || !root.isArray()) return GeocodeResult.FAILED;
        if (root.isEmpty()) return GeocodeResult.NOT_FOUND;
        JsonNode first = root.get(0);
        if (cc != null && !cc.equals(iso2(first.path("country").asText(null)))) return GeocodeResult.NOT_FOUND;
        Double lat = number(first.path("lat"));
        Double lon = number(first.path("lon"));
        if (lat == null || lon == null || Math.abs(lat) > 90 || Math.abs(lon) > 180) return GeocodeResult.FAILED;
        return GeocodeResult.found(new Point(lat, lon));
    }

    /** Parsed body of a 2xx answer, or null. Never lets an exception carrying the URI escape or reach the log. */
    private JsonNode get(URI uri, String what) {
        if (!throttle()) return null;
        try {
            return http.get().uri(uri).exchange((req, res) -> {
                int status = res.getStatusCode().value();
                if (status == 401) {
                    log.warn("[weather] OpenWeather rejected the API key (HTTP 401) on {}", what);
                    return null;
                }
                if (status == 429) {
                    log.warn("[weather] OpenWeather rate limit hit (HTTP 429) on {}", what);
                    return null;
                }
                if (status / 100 != 2) {
                    log.debug("[weather] OpenWeather {} answered HTTP {}", what, status);
                    return null;
                }
                try {
                    return JSON.readTree(res.getBody());
                } catch (Exception e) {
                    log.debug("[weather] OpenWeather {} body is not JSON", what);
                    return null;
                }
            }, true);
        } catch (Exception e) {
            // Class name only: Spring's I/O exception messages include the request URL and its appid.
            log.debug("[weather] OpenWeather {} failed: {}", what, e.getClass().getSimpleName());
            return null;
        }
    }

    /** Reserves the next slot; a wait over {@code maxWaitMillis} skips the call rather than firing early. */
    private boolean throttle() {
        long interval = Math.max(0, minIntervalMillis);
        if (interval == 0) return true;
        long now = clock.millis();
        long slot = nextCallAtMillis.getAndUpdate(prev -> Math.max(prev, now) + interval);
        long waitMs = Math.max(slot, now) - now;
        if (waitMs <= 0) return true;
        if (waitMs > maxWaitMillis) {
            // A skip hands its slot back while it is still the last one reserved, so skips cannot pile up.
            nextCallAtMillis.compareAndSet(Math.max(slot, now) + interval, slot);
            log.debug("[weather] throttle backlog {}ms exceeds {}ms, skipping the call", waitMs, maxWaitMillis);
            return false;
        }
        try {
            Thread.sleep(waitMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Double number(JsonNode n) {
        return n != null && n.isNumber() && Double.isFinite(n.asDouble()) ? n.asDouble() : null;
    }

    private static String iso2(String raw) {
        if (raw == null) return null;
        String s = raw.strip().toUpperCase(Locale.ROOT);
        return s.length() == 2 ? s : null;
    }

    private static String plain(double v) {
        return BigDecimal.valueOf(v).toPlainString();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
