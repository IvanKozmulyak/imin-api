package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PublicDataSourcesResponse.PublicDataSource;
import com.imin.iminapi.predictor.dto.ReforecastResult;
import com.imin.iminapi.predictor.service.WeatherService;
import com.imin.iminapi.predictor.service.WeatherService.Weather;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherClient;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Gating, coordinate choice and both caches, over the real client on a mocked HTTP server. */
class WeatherServiceTest {

    private static final String BASE = "https://openweather.test.invalid";
    private static final String FORECAST = BASE + "/data/2.5/forecast?";
    private static final String GEOCODE = BASE + "/geo/1.0/direct?";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");
    private static final LocalDate EVENT_DAY = LocalDate.parse("2026-06-03");

    /** A clock the test moves forward. */
    private static final class MovingClock extends Clock {
        private Instant now = T0;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovingClock clock = new MovingClock();
    private final PredictorProperties props = new PredictorProperties();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DataSourceCatalog catalog = mock(DataSourceCatalog.class);

    private WeatherService service() {
        when(catalog.byId("openweather")).thenReturn(Optional.of(new PublicDataSource("openweather", "OpenWeather",
                List.of("weather"), "ODbL 1.0", "https://opendatacommons.org/licenses/odbl/1-0/",
                "Weather data provided by OpenWeather", "https://openweathermap.org/", "active", null)));
        OpenWeatherProperties ow = new OpenWeatherProperties();
        ow.setApiKey("k");
        ow.setBaseUrl(BASE);
        return new WeatherService(props, clock, new OpenWeatherClient(builder, ow, clock, 0, 0), catalog);
    }

    private WeatherService enabled() {
        props.setWeatherEnabled(true);
        return service();
    }

    /** 40 three-hour steps from T0, as the free forecast returns; fully covers 2026-06-03 in Paris. */
    private static String forecastBody() {
        StringJoiner items = new StringJoiner(",");
        for (int i = 0; i < 40; i++) {
            long dt = T0.getEpochSecond() + i * 3 * 3600L;
            items.add("{\"dt\":" + dt + ",\"main\":{\"temp\":" + (10 + i % 8) + "},\"pop\":0." + (i % 8) + "}");
        }
        return "{\"cod\":\"200\",\"list\":[" + items + "]}";
    }

    private static final String LILLE = "[{\"name\":\"Lille\",\"lat\":50.6365654,\"lon\":3.0635282,\"country\":\"FR\"}]";

    @AfterEach
    void noUnexpectedRequests() {
        server.verify();
    }

    @Test
    void nullWhenDisabled() {
        WeatherService sut = service(); // the default is off

        assertThat(sut.forecast(48.85, 2.35, "Paris", "FR", PARIS, EVENT_DAY, 2)).isNull();
    }

    @Test
    void nullBeyondHorizonOrNegative() {
        WeatherService sut = enabled(); // default horizon 4

        assertThat(sut.forecast(48.85, 2.35, "Paris", "FR", PARIS, LocalDate.parse("2026-06-06"), 5)).isNull();
        assertThat(sut.forecast(48.85, 2.35, "Paris", "FR", PARIS, LocalDate.parse("2026-05-31"), -1)).isNull();
    }

    @Test
    void nullWhenNoDateOrZone() {
        WeatherService sut = enabled();

        assertThat(sut.forecast(48.85, 2.35, "Paris", "FR", PARIS, null, 2)).isNull();
        assertThat(sut.forecast(48.85, 2.35, "Paris", "FR", null, EVENT_DAY, 2)).isNull();
    }

    @Test
    void eventCoordsSkipGeocode() {
        server.expect(requestTo(startsWith(FORECAST)))
                .andExpect(queryParam("lat", "48.86"))
                .andExpect(queryParam("lon", "2.35"))
                .andRespond(withSuccess(forecastBody(), MediaType.APPLICATION_JSON));

        Weather w = enabled().forecast(48.85661, 2.35222, "Paris", "FR", PARIS, EVENT_DAY, 2);

        // 2026-06-03 in Paris = steps 2026-06-03T00:00Z..21:00Z, pops 0.4..0.7 and 0.0..0.3, temps 14..17 and 10..13
        assertThat(w).isEqualTo(new Weather(70, 17.0));
    }

    @Test
    void outOfRangeEventCoordsFallBackToGeocode() {
        server.expect(requestTo(startsWith(GEOCODE))).andRespond(withSuccess(LILLE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(FORECAST))).andRespond(withSuccess(forecastBody(), MediaType.APPLICATION_JSON));

        assertThat(enabled().forecast(95.0, 2.35, "Lille", "FR", PARIS, EVENT_DAY, 2)).isNotNull();
    }

    @Test
    void noCoordsNoCityIsNull() {
        WeatherService sut = enabled();

        assertThat(sut.forecast(null, null, " ", "FR", PARIS, EVENT_DAY, 2)).isNull();
        assertThat(sut.forecast(null, null, null, "FR", PARIS, EVENT_DAY, 2)).isNull();
    }

    @Test
    void geocodeThenForecast() {
        server.expect(requestTo(startsWith(GEOCODE)))
                .andExpect(queryParam("q", "Lille,FR"))
                .andRespond(withSuccess(LILLE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(FORECAST)))
                .andExpect(queryParam("lat", "50.64"))
                .andExpect(queryParam("lon", "3.06"))
                .andRespond(withSuccess(forecastBody(), MediaType.APPLICATION_JSON));

        assertThat(enabled().forecast(null, null, "Lille", "FR", PARIS, EVENT_DAY, 2)).isEqualTo(new Weather(70, 17.0));
    }

    @Test
    void notFoundCached() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(GEOCODE)))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        WeatherService sut = enabled();

        assertThat(sut.forecast(null, null, "Nowhere", "FR", PARIS, EVENT_DAY, 2)).isNull();
        assertThat(sut.forecast(null, null, "nowhere", "fr", PARIS, EVENT_DAY, 2)).isNull();
    }

    @Test
    void foundGeocodeCached() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(GEOCODE)))
                .andRespond(withSuccess(LILLE, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(startsWith(FORECAST)))
                .andRespond(withSuccess(forecastBody(), MediaType.APPLICATION_JSON));
        WeatherService sut = enabled();

        assertThat(sut.forecast(null, null, "Lille", "FR", PARIS, EVENT_DAY, 2)).isNotNull();
        assertThat(sut.forecast(null, null, "lille", "fr", PARIS, EVENT_DAY, 2)).isNotNull();
    }

    @Test
    void geocodeFailureNotCached() {
        server.expect(requestTo(startsWith(GEOCODE))).andRespond(withServerError());
        server.expect(requestTo(startsWith(GEOCODE))).andRespond(withSuccess(LILLE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(FORECAST))).andRespond(withSuccess(forecastBody(), MediaType.APPLICATION_JSON));
        WeatherService sut = enabled();

        assertThat(sut.forecast(null, null, "Lille", "FR", PARIS, EVENT_DAY, 2)).isNull();
        assertThat(sut.forecast(null, null, "Lille", "FR", PARIS, EVENT_DAY, 2)).isNotNull();
    }

    @Test
    void forecastCachedWithinTtlRefetchedAfter() {
        server.expect(ExpectedCount.times(2), requestTo(startsWith(FORECAST)))
                .andRespond(withSuccess(forecastBody(), MediaType.APPLICATION_JSON));
        WeatherService sut = enabled();

        assertThat(sut.forecast(48.85, 2.35, "Paris", "FR", PARIS, EVENT_DAY, 2)).isNotNull();
        clock.advance(Duration.ofMinutes(59));
        assertThat(sut.forecast(48.85, 2.35, "Paris", "FR", PARIS, EVENT_DAY, 2)).isNotNull();
        clock.advance(Duration.ofMinutes(2));
        assertThat(sut.forecast(48.85, 2.35, "Paris", "FR", PARIS, EVENT_DAY, 2)).isNotNull();
    }

    @Test
    void upstreamErrorIsNull() {
        server.expect(requestTo(startsWith(FORECAST))).andRespond(withServerError());

        assertThat(enabled().forecast(48.85, 2.35, "Paris", "FR", PARIS, EVENT_DAY, 2)).isNull();
    }

    @Test
    void creditFromCatalog() {
        assertThat(service().credit()).isEqualTo(new ReforecastResult.NarrationCredit(
                "Weather data provided by OpenWeather", "https://openweathermap.org/"));
    }

    @Test
    void missingCatalogEntryFailsConstruction() {
        when(catalog.byId("openweather")).thenReturn(Optional.empty());
        OpenWeatherClient client = new OpenWeatherClient(builder, new OpenWeatherProperties(), clock, 0, 0);

        assertThatThrownBy(() -> new WeatherService(props, clock, client, catalog))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openweather");
    }
}
