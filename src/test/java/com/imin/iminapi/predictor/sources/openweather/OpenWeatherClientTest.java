package com.imin.iminapi.predictor.sources.openweather;

import ch.qos.logback.classic.Level;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherClient.GeocodeResult;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherClient.GeocodeStatus;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherClient.Point;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherDay.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class OpenWeatherClientTest {

    private static final String BASE = "https://openweather.test.invalid";
    // Distinct placeholder, so a leak check cannot match ordinary log words.
    private static final String LEAK_KEY = "test-not-a-key";

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ch.qos.logback.classic.Logger clientLog =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OpenWeatherClient.class);
    private Level priorLevel;

    @BeforeEach
    void debugLogging() {
        // DEBUG on, so a leak through a debug line would be captured too.
        priorLevel = clientLog.getLevel();
        clientLog.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLogging() {
        clientLog.setLevel(priorLevel);
    }

    private OpenWeatherClient client(String key) {
        return client(key, 0, 0);
    }

    private OpenWeatherClient client(String key, long interval, long maxWait) {
        OpenWeatherProperties props = new OpenWeatherProperties();
        props.setApiKey(key);
        props.setBaseUrl(BASE);
        return new OpenWeatherClient(builder, props, clock, interval, maxWait);
    }

    private static final String FORECAST = """
            {"cod":"200","list":[
              {"dt":1780272000,"main":{"temp":14.2,"temp_max":15.0},"pop":0.1},
              {"dt":1780282800,"main":{"temp":16.5},"pop":0.35}
            ]}""";

    @Test
    void forecastRequestShape() {
        server.expect(requestTo(startsWith(BASE + "/data/2.5/forecast?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("lat", "48.86"))
                .andExpect(queryParam("lon", "2.35"))
                .andExpect(queryParam("units", "metric"))
                .andExpect(queryParam("appid", "k"))
                .andRespond(withSuccess(FORECAST, MediaType.APPLICATION_JSON));

        Optional<List<Step>> steps = client("k").forecast(48.86, 2.35);

        server.verify();
        assertThat(steps).contains(List.of(
                new Step(Instant.ofEpochSecond(1780272000L), 14.2, 0.1),
                new Step(Instant.ofEpochSecond(1780282800L), 16.5, 0.35)));
    }

    @Test
    void missingFieldsStayNull() {
        server.expect(requestTo(startsWith(BASE + "/data/2.5/forecast?")))
                .andRespond(withSuccess("{\"list\":[{\"dt\":1780272000,\"main\":{}}]}", MediaType.APPLICATION_JSON));

        assertThat(client("k").forecast(48.86, 2.35))
                .contains(List.of(new Step(Instant.ofEpochSecond(1780272000L), null, null)));
    }

    @Test
    void notJsonOrNoListIsEmpty() {
        server.expect(requestTo(startsWith(BASE + "/data/2.5/forecast?")))
                .andRespond(withSuccess("x", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/data/2.5/forecast?")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        OpenWeatherClient c = client("k");

        assertThat(c.forecast(48.86, 2.35)).isEmpty();
        assertThat(c.forecast(48.86, 2.35)).isEmpty();
        server.verify();
    }

    @Test
    void status401LogsWithoutKeyAndIsEmpty(CapturedOutput output) {
        server.expect(requestTo(startsWith(BASE + "/data/2.5/forecast?")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"cod\":401,\"message\":\"Invalid API key.\"}"));

        assertThat(client(LEAK_KEY).forecast(48.86, 2.35)).isEmpty();

        assertThat(output).contains("HTTP 401").doesNotContain(LEAK_KEY).doesNotContain("appid");
    }

    @Test
    void ioFailureLogsWithoutUrl(CapturedOutput output) {
        // Spring wraps the IOException in a ResourceAccessException whose message names the URL.
        server.expect(requestTo(startsWith(BASE + "/data/2.5/forecast?")))
                .andRespond(withException(new IOException("connect timed out")));
        server.expect(requestTo(startsWith(BASE + "/geo/1.0/direct?")))
                .andRespond(request -> {
                    throw new ResourceAccessException("I/O error on GET request for \"" + request.getURI() + "\"");
                });
        OpenWeatherClient c = client(LEAK_KEY);

        assertThat(c.forecast(48.86, 2.35)).isEmpty();
        assertThat(c.geocode("Lille", "FR")).isEqualTo(GeocodeResult.FAILED);

        server.verify();
        assertThat(output).contains("ResourceAccessException").doesNotContain("appid").doesNotContain(LEAK_KEY);
    }

    @Test
    void geocodeFound() {
        server.expect(requestTo(startsWith(BASE + "/geo/1.0/direct?")))
                .andExpect(queryParam("q", "Lille,FR"))
                .andExpect(queryParam("limit", "1"))
                .andExpect(queryParam("appid", "k"))
                .andRespond(withSuccess("[{\"name\":\"Lille\",\"lat\":50.6365654,\"lon\":3.0635282,\"country\":\"FR\"}]",
                        MediaType.APPLICATION_JSON));

        GeocodeResult r = client("k").geocode("Lille", "fr");

        server.verify();
        assertThat(r.status()).isEqualTo(GeocodeStatus.FOUND);
        assertThat(r.point()).isEqualTo(new Point(50.6365654, 3.0635282));
    }

    @Test
    void geocodeStripsCommasFromTheCity() {
        server.expect(requestTo(startsWith(BASE + "/geo/1.0/direct?")))
                .andExpect(queryParam("q", "Saint+Denis+Paris,FR"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client("k").geocode("Saint Denis,Paris", "FR");

        server.verify();
    }

    @Test
    void geocodeEmptyIsNotFound() {
        server.expect(requestTo(startsWith(BASE + "/geo/1.0/direct?")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client("k").geocode("Nowhere", "FR")).isEqualTo(GeocodeResult.NOT_FOUND);
    }

    @Test
    void geocodeCountryMismatchIsNotFound() {
        server.expect(requestTo(startsWith(BASE + "/geo/1.0/direct?")))
                .andRespond(withSuccess("[{\"name\":\"Paris\",\"lat\":33.66,\"lon\":-95.55,\"country\":\"US\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client("k").geocode("Paris", "FR")).isEqualTo(GeocodeResult.NOT_FOUND);
    }

    @Test
    void geocode500IsFailed() {
        server.expect(requestTo(startsWith(BASE + "/geo/1.0/direct?"))).andRespond(withServerError());

        assertThat(client("k").geocode("Lille", "FR")).isEqualTo(GeocodeResult.FAILED);
    }

    @Test
    void skippedCallsGiveTheirSlotBack() {
        server.expect(ExpectedCount.times(2), requestTo(startsWith(BASE + "/data/2.5/forecast?")))
                .andRespond(withSuccess(FORECAST, MediaType.APPLICATION_JSON));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-01T12:00:00Z"));
        Clock moving = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        OpenWeatherProperties props = new OpenWeatherProperties();
        props.setApiKey("k");
        props.setBaseUrl(BASE);
        OpenWeatherClient c = new OpenWeatherClient(builder, props, moving, 1_000, 0);

        assertThat(c.forecast(48.86, 2.35)).isPresent();
        assertThat(c.forecast(48.86, 2.35)).isEmpty(); // skipped
        assertThat(c.forecast(48.86, 2.35)).isEmpty(); // skipped
        now.set(now.get().plusMillis(1_000));          // one interval later
        assertThat(c.forecast(48.86, 2.35)).isPresent();

        server.verify();
    }

    @Test
    void status429LogsWithoutKeyAndIsEmpty(CapturedOutput output) {
        server.expect(requestTo(startsWith(BASE + "/data/2.5/forecast?")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(client(LEAK_KEY).forecast(48.86, 2.35)).isEmpty();

        assertThat(output).contains("HTTP 429").doesNotContain(LEAK_KEY).doesNotContain("appid");
    }

    @Test
    void geocodeOutOfRangeCoordinatesIsFailed() {
        server.expect(requestTo(startsWith(BASE + "/geo/1.0/direct?")))
                .andRespond(withSuccess("[{\"name\":\"Lille\",\"lat\":95.0,\"lon\":3.06,\"country\":\"FR\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client("k").geocode("Lille", "FR")).isEqualTo(GeocodeResult.FAILED);
    }

    @Test
    void throttleSkipsInsteadOfFiring() {
        server.expect(requestTo(startsWith(BASE + "/data/2.5/forecast?")))
                .andRespond(withSuccess(FORECAST, MediaType.APPLICATION_JSON));
        OpenWeatherClient c = client("k", 60_000, 0);

        assertThat(c.forecast(48.86, 2.35)).isPresent();
        assertThat(c.forecast(48.86, 2.35)).isEmpty();

        server.verify(); // exactly one request reached the server
    }
}
