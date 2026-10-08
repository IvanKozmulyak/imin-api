package com.imin.iminapi.predictor.sources.prim;

import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Disruption;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.LineRef;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Outcome;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Period;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PrimDisruptionsClientTest {

    private static final String URL = "https://prim.iledefrance-mobilites.fr/marketplace/disruptions_bulk/disruptions/v2";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private PrimDisruptionsClient client() {
        PrimProperties props = new PrimProperties();
        props.setApiKey("secret-key");
        return new PrimDisruptionsClient(builder, props);
    }

    private static String fixture() {
        try {
            return new ClassPathResource("predictor/prim/disruptions-bulk.json").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Disruption> fetchFixture() {
        server.expect(requestTo(URL)).andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));
        Outcome o = client().fetch();
        assertThat(o.status()).isEqualTo(Status.OK);
        return o.snapshot().disruptions().stream().collect(Collectors.toMap(Disruption::id, Function.identity()));
    }

    @Test
    void sendsKeyInApiKeyHeaderNotInUrl() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET)).andExpect(header("apiKey", "secret-key"))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        Outcome o = client().fetch();

        server.verify();
        assertThat(o.status()).isEqualTo(Status.OK);
        assertThat(o.snapshot().feedUpdatedAt()).isEqualTo(Instant.parse("2026-10-07T12:58:17.304Z"));
    }

    static Stream<Arguments> notOk() {
        return Stream.of(
                Arguments.of("401", withStatus(HttpStatus.UNAUTHORIZED), Status.REJECTED_KEY),
                Arguments.of("429", withStatus(HttpStatus.TOO_MANY_REQUESTS), Status.RATE_LIMITED),
                Arguments.of("500", withStatus(HttpStatus.INTERNAL_SERVER_ERROR), Status.FAILED),
                Arguments.of("timeout", withException(new SocketTimeoutException("read timed out")), Status.FAILED),
                Arguments.of("non-JSON", withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML), Status.UNUSABLE),
                Arguments.of("no disruptions", withSuccess("{\"lines\":[]}", MediaType.APPLICATION_JSON), Status.UNUSABLE),
                Arguments.of("no lines", withSuccess("{\"disruptions\":[]}", MediaType.APPLICATION_JSON), Status.UNUSABLE));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("notOk")
    void mapsEveryNotOkAnswer(String name, ResponseCreator response, Status expected) {
        server.expect(requestTo(URL)).andRespond(response);

        Outcome o = client().fetch();

        assertThat(o.status()).isEqualTo(expected);
        assertThat(o.snapshot()).isNull();
    }

    @Test
    void periodsAreParisLocal() {
        // 00:30 on 4 July in Paris is 22:30Z on 3 July: UTC and Paris disagree on the day
        Disruption d = fetchFixture().get("d-works-rer-b");

        assertThat(d.periods()).containsExactly(
                new Period(Instant.parse("2026-07-03T22:30:00Z"), Instant.parse("2026-07-04T00:00:00Z")));
        assertThat(d.lastUpdate()).isEqualTo(Instant.parse("2026-07-03T12:54:24Z"));
    }

    @Test
    void linesJoinByDisruptionIds() {
        Map<String, Disruption> byId = fetchFixture();

        // the bus object on the same disruption is dropped, the rail line kept at line level
        assertThat(byId.get("d-works-rer-b").lines())
                .containsExactly(new LineRef("line:IDFM:C01743", "RER B", "RapidTransit", "line"));
        assertThat(byId.get("d-stop-m1").lines())
                .containsExactly(new LineRef("line:IDFM:C01371", "M1", "Metro", "stop"));
        assertThat(byId.get("d-strike-t14").kind()).isEqualTo(PrimClassifier.STRIKE);
        assertThat(byId.get("d-works-rer-b").kind()).isEqualTo(PrimClassifier.WORKS);
        // the unparseable second period is dropped, the good one kept
        assertThat(byId.get("d-strike-t14").periods()).hasSize(1);
    }

    @Test
    void disruptionWithoutPeriodOrWithARepeatedIdIsDropped() {
        server.expect(requestTo(URL)).andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        Outcome o = client().fetch();

        assertThat(o.snapshot().disruptions()).extracting(Disruption::id)
                .containsExactlyInAnyOrder("d-works-rer-b", "d-strike-t14", "d-stop-m1");
        // the second d-stop-m1 is dropped and the first kept
        assertThat(o.snapshot().disruptions()).filteredOn(d -> d.id().equals("d-stop-m1")).singleElement()
                .extracting(Disruption::severity).isEqualTo("BLOQUANTE");
        assertThat(o.snapshot().dropped()).isEqualTo(2);
    }
}
