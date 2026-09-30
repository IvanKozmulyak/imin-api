package com.imin.iminapi.predictor.sources;

import com.imin.iminapi.predictor.calendar.CalendarSyncProperties;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.config.PredictorProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Maps a {@code sources.yaml} gate key to the live flag that turns that source on or off. */
@Component
public class SourceGates {

    private final Map<String, BooleanSupplier> gates;

    public SourceGates(CalendarSyncProperties calendar, PredictorProperties predictor, DateCheckProperties dateCheck) {
        // Calendar data reaches a predictor output only through the date check, which reads the synced table.
        this.gates = Map.of(
                "date-check", () -> Boolean.TRUE.equals(dateCheck.getEnabled())
                        && Boolean.TRUE.equals(calendar.getSyncEnabled()),
                "weather", predictor::isWeatherEnabled);
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
