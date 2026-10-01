package com.imin.iminapi.predictor.sources;

import com.imin.iminapi.predictor.calendar.CalendarSyncProperties;
import com.imin.iminapi.predictor.calendar.FootballDataProperties;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Maps a {@code sources.yaml} gate key to the live flag that turns that source on or off:
 * {@code date-check} (calendar data), {@code weather} (Open-Meteo), {@code wikimedia} (pageviews for 9.1),
 * {@code football} (football-data.org fixtures for 3.2).
 */
@Component
public class SourceGates {

    private final Map<String, BooleanSupplier> gates;

    public SourceGates(CalendarSyncProperties calendar, PredictorProperties predictor, DateCheckProperties dateCheck,
                       WikimediaProperties wikimedia, FootballDataProperties football) {
        // Calendar data reaches a predictor output only through the date check, which reads the synced table.
        this.gates = Map.of(
                "date-check", () -> Boolean.TRUE.equals(dateCheck.getEnabled())
                        && Boolean.TRUE.equals(calendar.getSyncEnabled()),
                "weather", predictor::isWeatherEnabled,
                // Pageviews reach an output only through question 9.1 of the date check.
                "wikimedia", () -> Boolean.TRUE.equals(dateCheck.getEnabled()) && wikimedia.isEnabled(),
                // Fixtures are calendar rows read only by question 3.2 of the date check.
                "football", () -> Boolean.TRUE.equals(dateCheck.getEnabled())
                        && Boolean.TRUE.equals(calendar.getSyncEnabled()) && football.isOn());
    }

    public Set<String> keys() {
        return gates.keySet();
    }

    /** Read at call time, so a flag flipped at runtime shows on the next request. */
    public boolean isOn(String key) {
        BooleanSupplier gate = gates.get(key);
        return gate != null && gate.getAsBoolean();
    }
}
