package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PublicDataSourcesResponse.PublicDataSource;
import com.imin.iminapi.predictor.dto.ReforecastResult;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherClient;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherClient.GeocodeResult;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherClient.GeocodeStatus;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherClient.Point;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weather signal for the live re-forecast, from OpenWeather's free 5-day / 3-hour forecast: the
 * max precipitation probability and max sampled temperature over the event's local day.
 *
 * <p>Returns null when disabled ({@code PREDICTOR_WEATHER_ENABLED}), outside
 * {@code [0, weather-max-horizon-days]}, without usable coordinates, when the day is not fully
 * forecast, or on ANY failure: the narrator then lists weather as UNKNOWN, never a guess. It seasons
 * the narration only; the pacing arithmetic ignores it, and pre-publish scoring never calls it.
 *
 * <p>Coordinates: the event's stored venue point first, else OpenWeather geocoding of city plus
 * country. In-memory caches: geocode answers (found / not found) for the process lifetime, a failed
 * lookup never; the forecast steps per rounded point for 1 h. Single-instance deploy.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    static final String SOURCE_ID = "openweather";
    // ponytail: 1 h TTL because the free plan's update interval is unverified; raise once it is known.
    private static final Duration FORECAST_TTL = Duration.ofHours(1);

    private final PredictorProperties props;
    private final Clock clock;
    private final OpenWeatherClient client;
    private final ReforecastResult.NarrationCredit credit;

    private final Map<String, GeocodeResult> geocodeCache = new ConcurrentHashMap<>();
    private final Map<String, CachedSteps> forecastCache = new ConcurrentHashMap<>();

    public WeatherService(PredictorProperties props, Clock clock, OpenWeatherClient client, DataSourceCatalog catalog) {
        this.props = props;
        this.clock = clock;
        this.client = client;
        PublicDataSource source = catalog.byId(SOURCE_ID).orElseThrow(() ->
                new IllegalStateException("predictor sources: no '" + SOURCE_ID + "' entry for the weather credit"));
        this.credit = new ReforecastResult.NarrationCredit(source.creditLine(), source.url());
    }

    /** Precipitation probability (%) and max temperature (°C) for the event date. */
    public record Weather(Integer precipProbabilityMaxPct, Double tempMaxC) {}

    private record CachedSteps(List<OpenWeatherDay.Step> steps, Instant fetchedAt) {}

    /** Visible attribution for a narration generated from this data. */
    public ReforecastResult.NarrationCredit credit() {
        return credit;
    }

    /**
     * Forecast for {@code eventDate} in {@code zone}, or null when disabled, out of horizon, without
     * coordinates, not fully covered, or on any failure. {@code daysOut} is the event's horizon in days.
     */
    public Weather forecast(Double lat, Double lon, String city, String country, ZoneId zone,
                            LocalDate eventDate, int daysOut) {
        if (!props.isWeatherEnabled()) return null;
        if (eventDate == null || zone == null) return null;
        if (daysOut < 0 || daysOut > props.getWeatherMaxHorizonDays()) return null;
        try {
            Point point = usable(lat, lon) ? new Point(lat, lon) : geocode(city, country);
            if (point == null) return null;
            double rLat = round2(point.lat());
            double rLon = round2(point.lon());
            List<OpenWeatherDay.Step> steps = cachedSteps(rLat, rLon);
            return steps == null ? null : OpenWeatherDay.of(steps, zone, eventDate);
        } catch (Exception e) {
            log.debug("[weather] lookup failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private List<OpenWeatherDay.Step> cachedSteps(double lat, double lon) {
        String key = lat + "," + lon;
        Instant now = clock.instant();
        CachedSteps cached = forecastCache.get(key);
        if (cached != null && fresh(cached, now)) return cached.steps();
        Optional<List<OpenWeatherDay.Step>> fetched = client.forecast(lat, lon);
        forecastCache.values().removeIf(c -> !fresh(c, now));
        if (fetched.isEmpty()) return null;
        forecastCache.put(key, new CachedSteps(List.copyOf(fetched.get()), now));
        return fetched.get();
    }

    private static boolean fresh(CachedSteps c, Instant now) {
        return Duration.between(c.fetchedAt(), now).compareTo(FORECAST_TTL) < 0;
    }

    /** Found and not-found answers are kept; a failed lookup is retried next time. */
    private Point geocode(String city, String country) {
        if (city == null || city.isBlank()) return null;
        String key = city.strip().toLowerCase(Locale.ROOT) + "|"
                + (country == null ? "" : country.strip().toLowerCase(Locale.ROOT));
        GeocodeResult cached = geocodeCache.get(key);
        if (cached != null) return cached.point();
        GeocodeResult looked = client.geocode(city, country);
        if (looked.status() == GeocodeStatus.FAILED) return null;
        geocodeCache.putIfAbsent(key, looked);
        return looked.point();
    }

    private static boolean usable(Double lat, Double lon) {
        return lat != null && lon != null && Double.isFinite(lat) && Double.isFinite(lon)
                && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
