package com.imin.iminapi.push;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link ExpoPushSender#send} driven for real, against a mocked Expo.
 *
 * <p><b>Why this file has to exist.</b> {@code DropAlertPushTest} exercises only
 * the static {@code batch()}, and {@code DropAlertFanOutTest} mocks the sender
 * outright — so between them nothing ever runs {@code send()}, and
 * {@code readTickets} is where the dead-token pruning lives. Reading
 * {@code ticket.error} instead of {@code ticket.details.error}, or getting the
 * casing of {@code DeviceNotRegistered} wrong, would make {@code deadTokens}
 * permanently empty and leave the registry to rot — with every other test in
 * this task still green, because they stub the {@code Result}.
 */
class ExpoPushSenderTest {

    private static final String URL = "https://exp.host/--/api/v2/push/send";
    private static final String ALIVE = "ExponentPushToken[alive0000000000000000]";
    private static final String DEAD = "ExponentPushToken[dead00000000000000000]";
    private static final String BUSY = "ExponentPushToken[busy00000000000000000]";

    private record Harness(ExpoPushSender sender, MockRestServiceServer server) {}

    /**
     * The request factory is bound by {@link MockRestServiceServer} on the way
     * in, which is why {@link ExpoPushSender} takes an already-built client
     * rather than building one itself: a constructor that called
     * {@code requestFactory(...)} would overwrite the mock and this suite would
     * quietly hit the real exp.host.
     */
    private static Harness harness(PushProperties props) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Harness(new ExpoPushSender(props, builder.build()), server);
    }

    private static PushProperties enabled() {
        PushProperties props = new PushProperties();
        props.setEnabled(true);
        props.setBaseUrl(URL);
        return props;
    }

    private static PushMessage message(String token) {
        return new PushMessage(token, "Tickets are live", "Vechirka",
                PushMessage.CHANNEL_DROP_ALERTS, Map.of("eventId", "e1"));
    }

    // ── The dead-token rule ────────────────────────────────────────────────

    /**
     * THE assertion this class exists for: only {@code DeviceNotRegistered} is
     * dead. {@code MessageRateExceeded} describes a device that is very much
     * alive and merely throttled — revoking it would silently unsubscribe the
     * users who get the most notifications.
     */
    @Test
    void onlyDeviceNotRegisteredIsRevoked_aRateLimitedDeviceIsLeftAlone() {
        Harness h = harness(enabled());
        h.server().expect(requestTo(URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$[0].to").value(ALIVE))
                .andExpect(jsonPath("$[0].channelId").value(PushMessage.CHANNEL_DROP_ALERTS))
                .andExpect(jsonPath("$[0].data.eventId").value("e1"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"status":"ok","id":"ticket-1"},
                          {"status":"error","message":"not registered",
                           "details":{"error":"DeviceNotRegistered"}},
                          {"status":"error","message":"slow down",
                           "details":{"error":"MessageRateExceeded"}}
                        ]}""", MediaType.APPLICATION_JSON));

        ExpoPushSender.Result result = h.sender()
                .send(List.of(message(ALIVE), message(DEAD), message(BUSY)));

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.deadTokens()).containsExactly(DEAD);
        h.server().verify();
    }

    // ── The dark switch ────────────────────────────────────────────────────

    /**
     * Disabled means <b>no HTTP call at all</b>, not a call whose result is
     * discarded. {@code never()} on the expectation is what makes that the
     * assertion rather than a hope.
     */
    @Test
    void disabledSenderMakesNoHttpCallWhatsoever() {
        PushProperties props = new PushProperties(); // enabled defaults to false
        props.setBaseUrl(URL);
        Harness h = harness(props);
        h.server().expect(never(), requestTo(URL)).andRespond(withSuccess());

        ExpoPushSender.Result result = h.sender().send(List.of(message(ALIVE)));

        assertThat(result).isEqualTo(ExpoPushSender.Result.NONE);
        h.server().verify();
    }

    // ── Failure never escapes ──────────────────────────────────────────────

    @Test
    void aNon2xxResponseYieldsNoneAndThrowsNothing() {
        Harness h = harness(enabled());
        h.server().expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ExpoPushSender.Result[] result = new ExpoPushSender.Result[1];
        assertThatCode(() -> result[0] = h.sender().send(List.of(message(ALIVE))))
                .doesNotThrowAnyException();

        assertThat(result[0]).isEqualTo(ExpoPushSender.Result.NONE);
        h.server().verify();
    }

    /**
     * Expo is documented to return one ticket per message, but a truncated array
     * must degrade rather than throw: the caller is a best-effort fan-out whose
     * exception handling would swallow the cause and log a one-liner.
     */
    @Test
    void aShortTicketArrayDoesNotOverrunTheChunk() {
        Harness h = harness(enabled());
        h.server().expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"data":[{"status":"ok","id":"ticket-1"}]}""",
                        MediaType.APPLICATION_JSON));

        ExpoPushSender.Result result = h.sender()
                .send(List.of(message(ALIVE), message(DEAD), message(BUSY)));

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.deadTokens()).isEmpty();
        h.server().verify();
    }

    @Test
    void aResponseWithNoDataArrayIsSurvived() {
        Harness h = harness(enabled());
        h.server().expect(requestTo(URL))
                .andRespond(withSuccess("{\"errors\":[{\"code\":\"nope\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(h.sender().send(List.of(message(ALIVE))))
                .isEqualTo(ExpoPushSender.Result.NONE);
        h.server().verify();
    }

    // ── Credentials ────────────────────────────────────────────────────────

    @Test
    void theAccessTokenIsSentAsABearerHeaderWhenSet() {
        PushProperties props = enabled();
        props.setAccessToken("expo-secret");
        Harness h = harness(props);
        h.server().expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer expo-secret"))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\"}]}", MediaType.APPLICATION_JSON));

        h.sender().send(List.of(message(ALIVE)));
        h.server().verify();
    }

    @Test
    void noAuthorizationHeaderIsSentWhenTheAccessTokenIsBlank() {
        Harness h = harness(enabled()); // accessToken defaults to ""
        h.server().expect(requestTo(URL))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\"}]}", MediaType.APPLICATION_JSON));

        h.sender().send(List.of(message(ALIVE)));
        h.server().verify();
    }

    // ── The timeout is wired, not merely configured ────────────────────────

    /**
     * {@code props.getTimeoutSeconds()} being read by nothing is not a cosmetic
     * defect — see {@code PushConfig.httpClientSettings}'s Javadoc. This pins
     * that the property reaches the settings the request factory is built from,
     * in both directions.
     */
    @Test
    void theConfiguredTimeoutReachesTheHttpClientSettings() {
        PushProperties props = new PushProperties();
        props.setTimeoutSeconds(7);

        assertThat(PushConfig.httpClientSettings(props).connectTimeout())
                .isEqualTo(Duration.ofSeconds(7));
        assertThat(PushConfig.httpClientSettings(props).readTimeout())
                .isEqualTo(Duration.ofSeconds(7));
        // And the factory the bean is built from actually accepts them.
        assertThat(new PushConfig().expoRestClient(props)).isNotNull();
    }
}
