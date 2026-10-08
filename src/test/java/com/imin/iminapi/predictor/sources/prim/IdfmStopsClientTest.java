package com.imin.iminapi.predictor.sources.prim;

import com.imin.iminapi.predictor.sources.prim.IdfmStopsClient.Outcome;
import com.imin.iminapi.predictor.sources.prim.IdfmStopsClient.Stop;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdfmStopsClientTest {

    private static final String HEADER = "arrid;zdaid;arrgeopoint\n";

    /** {@code count} valid rows in one filler zone, enough to pass the row minimum on their own. */
    private static String filler(int count) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) b.append(500_000 + i).append(";999999;48.85, 2.35\n");
        return b.toString();
    }

    private static Map<String, Stop> byRef(List<Stop> stops) {
        return stops.stream().collect(Collectors.toMap(Stop::ref, Function.identity()));
    }

    @Test
    void parsesStopsAndZoneCentroids() {
        String body = "﻿" + HEADER + "22088;58879;48.8606, 2.3376\r\n22089;58879;48.8610, 2.3390\r\n"
                + filler(IdfmStopsClient.MIN_ROWS);

        Map<String, Stop> stops = byRef(IdfmStopsClient.parse(body));

        assertThat(stops.get("IDFM:22088")).isEqualTo(new Stop("IDFM:22088", 48.8606, 2.3376));
        assertThat(stops.get("IDFM:22089")).isEqualTo(new Stop("IDFM:22089", 48.8610, 2.3390));
        Stop zone = stops.get("IDFM:monomodalStopPlace:58879");
        assertThat(zone.lat()).isCloseTo(48.8608, within(1e-9));
        assertThat(zone.lng()).isCloseTo(2.3383, within(1e-9));
        assertThat(stops).hasSize(IdfmStopsClient.MIN_ROWS + 2 + 2);
    }

    @ParameterizedTest(name = "skips \"{0}\"")
    @ValueSource(strings = {
            "77001;88001;",               // blank point
            "77001;88001;abc",            // unparsable point
            "77001;88001;43.2965, 5.3698", // Marseille, outside the IDF box
            "7700a;88001;48.8606, 2.3376", // non-digit stop id
            "77001;8800z;48.8606, 2.3376"}) // non-digit zone id
    void skipsBadRows(String row) {
        String body = HEADER + row + "\n" + filler(IdfmStopsClient.MIN_ROWS);

        Map<String, Stop> stops = byRef(IdfmStopsClient.parse(body));

        assertThat(stops).hasSize(IdfmStopsClient.MIN_ROWS + 1)
                .doesNotContainKeys("IDFM:77001", "IDFM:7700a", "IDFM:monomodalStopPlace:88001",
                        "IDFM:monomodalStopPlace:8800z");
    }

    static Stream<Arguments> unusable() {
        return Stream.of(
                Arguments.of("no arrgeopoint column", "arrid;zdaid;geo\n" + filler(IdfmStopsClient.MIN_ROWS)),
                Arguments.of("one row short of the minimum", HEADER + filler(IdfmStopsClient.MIN_ROWS - 1)),
                Arguments.of("empty body", ""));
    }

    @ParameterizedTest(name = "{0} is unusable")
    @MethodSource
    void unusable(String name, String body) {
        assertThat(IdfmStopsClient.parse(body)).isEmpty();

        MockRestServiceServer server = bind();
        server.expect(requestTo(IdfmStopsClient.URL)).andRespond(withSuccess(body, MediaType.TEXT_PLAIN));
        Outcome o = client.fetch();
        assertThat(o.status()).isEqualTo(Status.UNUSABLE);
        assertThat(o.stops()).isEmpty();
    }

    @Test
    void okBodyIsOkWithStops() {
        String body = HEADER + filler(IdfmStopsClient.MIN_ROWS);
        MockRestServiceServer server = bind();
        server.expect(requestTo(IdfmStopsClient.URL)).andRespond(withSuccess(body, MediaType.TEXT_PLAIN));

        Outcome o = client.fetch();

        assertThat(o.status()).isEqualTo(Status.OK);
        assertThat(o.stops()).hasSize(IdfmStopsClient.MIN_ROWS + 1);
    }

    static Stream<Arguments> httpStatus() {
        return Stream.of(
                Arguments.of("500", withStatus(HttpStatus.INTERNAL_SERVER_ERROR)),
                Arguments.of("IOException", withException(new IOException("connection reset"))));
    }

    @ParameterizedTest(name = "{0} -> failed")
    @MethodSource
    void httpStatus(String name, ResponseCreator response) {
        MockRestServiceServer server = bind();
        server.expect(requestTo(IdfmStopsClient.URL)).andRespond(response);

        Outcome o = client.fetch();

        assertThat(o.status()).isEqualTo(Status.FAILED);
        assertThat(o.stops()).isEmpty();
    }

    private IdfmStopsClient client;

    private MockRestServiceServer bind() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        client = new IdfmStopsClient(builder);
        return server;
    }
}
