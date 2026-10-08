package com.imin.iminapi.predictor.sources;

import com.imin.iminapi.predictor.calendar.CalendarSyncProperties;
import com.imin.iminapi.predictor.calendar.FootballDataProperties;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsProperties;
import com.imin.iminapi.predictor.sources.prim.PrimProperties;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Maps a {@code sources.yaml} gate key to the live flag that turns that source on or off:
 * {@code date-check} (calendar data), {@code weather} (OpenWeather), {@code wikimedia} (pageviews for 9.1),
 * {@code football} (football-data.org fixtures for 3.2), {@code openagenda} and {@code quefaireaparis} (open event
 * listings counted into genre_week_count), {@code prim} (IDFM PRIM traffic messages for 6.1/6.2). Each source's sync job
 * and every evaluator reading its data check the gate.
 */
@Component
public class SourceGates {

    private static final Logger log = LoggerFactory.getLogger(SourceGates.class);

    private final Map<String, BooleanSupplier> gates;

    public SourceGates(CalendarSyncProperties calendar, PredictorProperties predictor, DateCheckProperties dateCheck,
                       WikimediaProperties wikimedia, FootballDataProperties football, OpenEventsProperties openEvents,
                       PrimProperties prim) {
        // Calendar data reaches a predictor output only through the date check, which reads the synced table.
        this.gates = Map.of(
                "date-check", () -> Boolean.TRUE.equals(dateCheck.getEnabled())
                        && Boolean.TRUE.equals(calendar.getSyncEnabled()),
                "weather", predictor::isWeatherEnabled,
                // Pageviews reach an output only through question 9.1 of the date check.
                "wikimedia", () -> Boolean.TRUE.equals(dateCheck.getEnabled()) && wikimedia.isEnabled(),
                // Fixtures are calendar rows read only by question 3.2 of the date check.
                "football", () -> Boolean.TRUE.equals(dateCheck.getEnabled())
                        && Boolean.TRUE.equals(calendar.getSyncEnabled()) && football.isOn(),
                // Open listings reach an output only through the date check.
                "openagenda", () -> Boolean.TRUE.equals(dateCheck.getEnabled()) && openEvents.isOpenagendaEnabled()
                        && !openEvents.getOpenagendaApiKey().isBlank(),
                "quefaireaparis", () -> Boolean.TRUE.equals(dateCheck.getEnabled()) && openEvents.isQuefaireaparisEnabled(),
                // Traffic messages reach an output only through questions 6.1/6.2 of the date check.
                "prim", () -> Boolean.TRUE.equals(dateCheck.getEnabled()) && prim.isOn());
    }

    public Set<String> keys() {
        return gates.keySet();
    }

    /** One boot line with each gate on/off (flags only, never a key), so the deploy log shows what the process sees. */
    @EventListener(ApplicationReadyEvent.class)
    public void logGates() {
        log.info("Predictor source gates: {}", new TreeSet<>(gates.keySet()).stream()
                .map(key -> key + "=" + (isOn(key) ? "on" : "off"))
                .collect(Collectors.joining(" ")));
    }

    /** Read at call time, so a flag flipped at runtime shows on the next request. */
    public boolean isOn(String key) {
        BooleanSupplier gate = gates.get(key);
        return gate != null && gate.getAsBoolean();
    }
}
