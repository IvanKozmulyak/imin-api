package com.imin.iminapi.predictor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.imin.iminapi.predictor.config.PredictorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weather signal for the live re-forecast (predictor task scope A — founder override of spec §6.3
 * "weather explicitly out", implemented LEAN). Open-Meteo's free API (no key): geocode the venue
 * city, then read precipitation probability + max temperature for the event date.
 *
 * <p><b>Only for re-forecast, only within the horizon.</b> Returns null when disabled
 * ({@code imin.predictor.weather-enabled=false}), when the event is more than
 * {@code weather-max-horizon-days} out (a forecast that far is not reliable), or on ANY failure —
 * it is listed to the LLM as UNKNOWN, never fabricated, and never blocks. It seasons the narrator
 * only; the pacing arithmetic ignores it. <b>It NEVER touches pre-publish scores</b> — those are
 * made at horizons where weather is unforecastable, so this service is not wired into the
 * pre-publish snapshot at all.
 *
 * <p>Two in-memory caches: geocoded coordinates per city (stable), and the forecast per
 * (coords, date) for ~6h. Single-instance deploy — a restart just re-warms them.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final Duration FORECAST_TTL = Duration.ofHours(6);

    private final PredictorProperties props;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final Map<String, Optional<double[]>> coordsCache = new ConcurrentHashMap<>();
    private final Map<String, CachedForecast> forecastCache = new ConcurrentHashMap<>();

    public WeatherService(PredictorProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /** Precipitation probability (%) and max temperature (°C) for the event date. */
    public record Weather(Integer precipProbabilityMaxPct, Double tempMaxC) {}

    private record CachedForecast(Weather weather, Instant at) {}

    /**
     * Forecast for {@code eventDate}, or null when disabled, beyond horizon, missing a city, or on
     * any failure. {@code daysOut} is the event's horizon in days.
     */
    public Weather forecast(String city, String country, LocalDate eventDate, int daysOut) {
        if (!props.isWeatherEnabled()) return null;
        if (city == null || city.isBlank() || eventDate == null) return null;
        if (daysOut < 0 || daysOut > props.getWeatherMaxHorizonDays()) return null; // unforecastable horizon
        try {
            double[] coords = geocode(city, country);
            if (coords == null) return null;
            return cachedForecast(coords[0], coords[1], eventDate);
        } catch (Exception e) {
            log.debug("[weather] lookup failed for {} on {}: {} — treated as unknown", city, eventDate, e.getMessage());
            return null; // never fabricate, never block
        }
    }

    private Weather cachedForecast(double lat, double lon, LocalDate date) throws Exception {
        String key = lat + "," + lon + "," + date;
        CachedForecast cached = forecastCache.get(key);
        if (cached != null && Duration.between(cached.at(), clock.instant()).compareTo(FORECAST_TTL) < 0) {
            return cached.weather();
        }
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&daily=precipitation_probability_max,temperature_2m_max&timezone=auto"
                + "&start_date=" + date + "&end_date=" + date;
        JsonNode daily = getJson(url).path("daily");
        Integer precip = intAt(daily.path("precipitation_probability_max"));
        Double temp = doubleAt(daily.path("temperature_2m_max"));
        Weather w = (precip == null && temp == null) ? null : new Weather(precip, temp);
        forecastCache.put(key, new CachedForecast(w, clock.instant()));
        return w;
    }

    private double[] geocode(String city, String country) {
        String key = city.toLowerCase() + "|" + (country == null ? "" : country.toLowerCase());
        return coordsCache.computeIfAbsent(key, k -> {
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                        + URLEncoder.encode(city, StandardCharsets.UTF_8)
                        + (country == null || country.isBlank() ? "" : "&country=" + URLEncoder.encode(country, StandardCharsets.UTF_8));
                JsonNode results = getJson(url).path("results");
                if (!results.isArray() || results.isEmpty()) return Optional.empty();
                JsonNode first = results.get(0);
                return Optional.of(new double[]{first.path("latitude").asDouble(), first.path("longitude").asDouble()});
            } catch (Exception e) {
                log.debug("[weather] geocode failed for {}: {}", city, e.getMessage());
                return Optional.empty();
            }
        }).orElse(null);
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
        return PredictorJson.MAPPER.readTree(res.body());
    }

    private static Integer intAt(JsonNode arr) {
        return arr.isArray() && !arr.isEmpty() && !arr.get(0).isNull() ? arr.get(0).asInt() : null;
    }

    private static Double doubleAt(JsonNode arr) {
        return arr.isArray() && !arr.isEmpty() && !arr.get(0).isNull() ? arr.get(0).asDouble() : null;
    }
}
