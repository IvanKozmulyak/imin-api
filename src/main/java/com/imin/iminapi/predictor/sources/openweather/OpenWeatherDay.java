package com.imin.iminapi.predictor.sources.openweather;

import com.imin.iminapi.predictor.service.WeatherService;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rolls one local calendar day up from OpenWeather's 3-hour forecast steps. */
public final class OpenWeatherDay {

    /** One {@code list[]} item: {@code dt}, {@code main.temp} (°C) and {@code pop} (0..1); missing fields stay null. */
    public record Step(Instant dt, Double tempC, Double pop) {}

    private OpenWeatherDay() {}

    /**
     * Max {@code pop} as a percentage and max sampled temperature over {@code date} in {@code zone},
     * or null when the day is not fully covered (today's past hours, or past the 5-day forecast).
     */
    public static WeatherService.Weather of(List<Step> steps, ZoneId zone, LocalDate date) {
        if (steps == null || zone == null || date == null) return null;
        ZonedDateTime dayStart = date.atStartOfDay(zone);
        ZonedDateTime dayEnd = date.plusDays(1).atStartOfDay(zone);
        Instant from = dayStart.toInstant();
        Instant to = dayEnd.toInstant();

        // Keyed by instant so a repeated step cannot fake coverage.
        Map<Instant, Step> inDay = new LinkedHashMap<>();
        for (Step s : steps) {
            if (s == null || s.dt() == null) continue;
            if (!s.dt().isBefore(from) && s.dt().isBefore(to)) inDay.putIfAbsent(s.dt(), s);
        }
        long needed = Duration.between(from, to).toHours() / 3;
        if (inDay.size() < needed) return null;

        Double maxPop = null;
        boolean popComplete = true;
        Double maxTemp = null;
        boolean tempComplete = true;
        for (Step s : inDay.values()) {
            if (s.pop() == null || !Double.isFinite(s.pop())) popComplete = false;
            else maxPop = maxPop == null ? s.pop() : Math.max(maxPop, s.pop());
            if (s.tempC() == null || !Double.isFinite(s.tempC())) tempComplete = false;
            else maxTemp = maxTemp == null ? s.tempC() : Math.max(maxTemp, s.tempC());
        }
        Integer precip = popComplete && maxPop != null
                ? (int) Math.max(0, Math.min(100, Math.round(maxPop * 100))) : null;
        Double temp = tempComplete ? maxTemp : null;
        if (precip == null && temp == null) return null;
        return new WeatherService.Weather(precip, temp);
    }
}
