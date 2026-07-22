package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.service.WeatherService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task scope A (weather): the gating that returns null WITHOUT any network call — disabled,
 * beyond horizon, or missing a city. The live Open-Meteo path is not exercised in tests (network).
 */
class WeatherServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    private final PredictorProperties props = new PredictorProperties();
    private final WeatherService sut = new WeatherService(props, clock);

    @Test
    void nullWhenDisabled() {
        props.setWeatherEnabled(false);
        assertThat(sut.forecast("Amsterdam", "NL", LocalDate.parse("2026-06-05"), 4)).isNull();
    }

    @Test
    void nullBeyondHorizon() {
        props.setWeatherEnabled(true); // default horizon 14 days
        assertThat(sut.forecast("Amsterdam", "NL", LocalDate.parse("2026-07-01"), 30)).isNull();
    }

    @Test
    void nullWhenNoCity() {
        props.setWeatherEnabled(true);
        assertThat(sut.forecast(null, "NL", LocalDate.parse("2026-06-05"), 4)).isNull();
        assertThat(sut.forecast("  ", "NL", LocalDate.parse("2026-06-05"), 4)).isNull();
    }
}
