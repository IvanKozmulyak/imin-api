package com.imin.iminapi.marketing.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.service.ai.provenance.AiEmailDisclosure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * The batch call on the wire: headers and body out, and how every Resend answer is classified. Every response body
 * below is from Resend docs, pending A0 verification (send-batch-emails, errors, idempotency-keys): none was captured
 * from a live call yet; the A0 checklist in the plan says how to replace them.
 */
class CampaignEmailProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URL = "https://api.resend.com/emails/batch";

    private MockRestServiceServer server;
    private CampaignEmailProvider provider;
    private MarketingEmailProperties props;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com");
        server = MockRestServiceServer.bindTo(builder).build();
        props = new MarketingEmailProperties();
        EmailProperties transactional = new EmailProperties();
        transactional.setApiKey("re_transactional");
        provider = new CampaignEmailProvider(builder.build(), props, transactional);
    }

    private static List<CampaignEmailProvider.OutgoingEmail> three() {
        return List.of(email("a@fan.fr"), email("b@fan.fr"), email("c@fan.fr"));
    }

    private static CampaignEmailProvider.OutgoingEmail email(String to) {
        return new CampaignEmailProvider.OutgoingEmail("\"Club via IMIN\" <news@news.imin.wtf>", to, "Subject",
                "<p>h</p>", "t", "https://api.imin.wtf/api/v1/public/unsubscribe/T");
    }

    private static ResponseCreator json(HttpStatus status, String body) {
        return withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    static Stream<Arguments> resendResponses() {
        return Stream.of(
                Arguments.of("200 strict", json(HttpStatus.OK,
                        "{\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"},{\"id\":\"m3\"}]}"), "ids:m1,m2,m3;errors:;unaccounted:"),
                Arguments.of("200 permissive, row 1 refused", json(HttpStatus.OK,
                        "{\"data\":[{\"id\":\"m1\"},{\"id\":\"m3\"}],"
                                + "\"errors\":[{\"index\":1,\"message\":\"Invalid `to` field.\"}]}"),
                        "ids:m1,-,m3;errors:1;unaccounted:"),
                Arguments.of("200 permissive, id count does not add up", json(HttpStatus.OK,
                        "{\"data\":[{\"id\":\"m1\"}],"
                                + "\"errors\":[{\"index\":1,\"message\":\"Invalid `to` field.\"}]}"),
                        "ids:-,-,-;errors:1;unaccounted:0,2"),
                Arguments.of("401 revoked key", json(HttpStatus.UNAUTHORIZED,
                        "{\"statusCode\":401,\"name\":\"missing_api_key\",\"message\":\"Missing API key\"}"),
                        "rejected:401"),
                Arguments.of("403 unverified domain", json(HttpStatus.FORBIDDEN,
                        "{\"statusCode\":403,\"name\":\"validation_error\","
                                + "\"message\":\"The news.imin.wtf domain is not verified.\"}"),
                        "rejected:403"),
                Arguments.of("422 request-level", json(HttpStatus.UNPROCESSABLE_ENTITY,
                        "{\"statusCode\":422,\"name\":\"validation_error\",\"message\":\"Invalid `from` field.\"}"),
                        "rejected:422"),
                Arguments.of("409 replay with a different body", json(HttpStatus.CONFLICT,
                        "{\"statusCode\":409,\"name\":\"invalid_idempotent_request\","
                                + "\"message\":\"Same idempotency key used with a different request payload.\"}"),
                        "mismatch"),
                Arguments.of("409 same key still in flight", json(HttpStatus.CONFLICT,
                        "{\"statusCode\":409,\"name\":\"concurrent_idempotent_requests\","
                                + "\"message\":\"Same idempotency key used while original request is still in progress.\"}"),
                        "retry:409:false:-"),
                Arguments.of("429 with retry-after", withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON).header("retry-after", "7")
                        .body("{\"statusCode\":429,\"name\":\"rate_limit_exceeded\",\"message\":\"Too many requests.\"}"),
                        "retry:429:false:7"),
                Arguments.of("500", json(HttpStatus.INTERNAL_SERVER_ERROR,
                        "{\"statusCode\":500,\"name\":\"internal_server_error\",\"message\":\"Unexpected error.\"}"),
                        "retry:500:false:-"),
                Arguments.of("read timeout", (ResponseCreator) request -> {
                    throw new SocketTimeoutException("Read timed out");
                }, "retry:0:true:-"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resendResponses")
    void classifiesEveryResendResponse(String label, ResponseCreator response, String expected) {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST)).andRespond(response);

        if (expected.startsWith("ids:")) {
            CampaignEmailProvider.BatchResult r = provider.sendBatch("campaign:c:k", three());
            String[] parts = expected.split(";");
            List<String> ids = Arrays.stream(parts[0].substring(4).split(",")).map(s -> "-".equals(s) ? null : s).toList();
            assertThat(r.ids()).containsExactlyElementsOf(ids);
            String errors = parts[1].substring(7);
            assertThat(r.rowErrors().keySet()).containsExactlyElementsOf(
                    errors.isEmpty() ? List.of() : Arrays.stream(errors.split(",")).map(Integer::parseInt).toList());
            // A row the answer does not account for is neither sent nor refused: its outcome is unknown.
            String unaccounted = parts[2].substring(12);
            List<Integer> expectUnconfirmed = unaccounted.isEmpty() ? List.of()
                    : Arrays.stream(unaccounted.split(",")).map(Integer::parseInt).toList();
            for (int i = 0; i < 3; i++) assertThat(r.unconfirmed(i)).as("row %d", i).isEqualTo(expectUnconfirmed.contains(i));
        } else if (expected.startsWith("rejected:")) {
            assertThatThrownBy(() -> provider.sendBatch("campaign:c:k", three()))
                    .isInstanceOfSatisfying(CampaignEmailProvider.CampaignRejected.class,
                            e -> assertThat(e.upstreamStatus()).isEqualTo(Integer.parseInt(expected.substring(9))));
        } else if (expected.equals("mismatch")) {
            assertThatThrownBy(() -> provider.sendBatch("campaign:c:k", three()))
                    .isInstanceOf(CampaignEmailProvider.ReplayMismatch.class);
        } else {
            String[] p = expected.split(":");
            assertThatThrownBy(() -> provider.sendBatch("campaign:c:k", three()))
                    .isInstanceOfSatisfying(CampaignEmailProvider.RetryableBatchFailure.class, e -> {
                        assertThat(e.upstreamStatus()).isEqualTo(Integer.parseInt(p[1]));
                        assertThat(e.transport()).isEqualTo(Boolean.parseBoolean(p[2]));
                        assertThat(e.retryAfter()).isEqualTo("-".equals(p[3]) ? null : Duration.ofSeconds(Long.parseLong(p[3])));
                    });
        }
        server.verify();
    }

    @ParameterizedTest(name = "marketing key \"{0}\", idempotency key \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "re_marketing|Bearer re_marketing|campaign:c1:k1|hello@club.fr",
            "''|Bearer re_transactional|''|''"
    })
    void sendsTheKeyReplyToTagsAndMarketingKey(String marketingKey, String authorization, String idempotencyKey,
                                               String replyTo) throws Exception {
        props.setResendApiKey(marketingKey);
        String key = idempotencyKey.isEmpty() ? null : idempotencyKey;
        UUID campaignId = key == null ? null : UUID.randomUUID();
        AtomicReference<HttpHeaders> headers = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST)).andExpect(request -> {
            headers.set(request.getHeaders());
            body.set(request.getBody().toString());
        }).andRespond(json(HttpStatus.OK, "{\"data\":[{\"id\":\"m1\"}]}"));

        provider.sendBatch(key, List.of(new CampaignEmailProvider.OutgoingEmail(
                "\"Club via IMIN\" <news@news.imin.wtf>", "fan@club.fr", "Subject", "<p>h</p>", "t",
                "https://api.imin.wtf/api/v1/public/unsubscribe/T", AiEmailDisclosure.NONE,
                replyTo.isEmpty() ? null : replyTo, campaignId)));

        server.verify();
        assertThat(headers.get().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(authorization);
        assertThat(headers.get().getFirst("x-batch-validation")).isEqualTo("permissive");
        assertThat(headers.get().getFirst("Idempotency-Key")).isEqualTo(key);
        JsonNode sent = JSON.readTree(body.get()).get(0);
        assertThat(sent.path("to").get(0).asText()).isEqualTo("fan@club.fr");
        assertThat(sent.path("headers").path("List-Unsubscribe").asText())
                .isEqualTo("<https://api.imin.wtf/api/v1/public/unsubscribe/T>");
        assertThat(sent.path("headers").path("List-Unsubscribe-Post").asText()).isEqualTo("List-Unsubscribe=One-Click");
        if (key == null) {
            assertThat(sent.has("reply_to")).isFalse();
            assertThat(sent.has("tags")).isFalse();
        } else {
            assertThat(sent.path("reply_to").asText()).isEqualTo(replyTo);
            assertThat(JSON.convertValue(sent.path("tags"), List.class))
                    .containsExactly(Map.of("name", "imin_campaign", "value", campaignId.toString()));
        }
    }

    @Test
    void rejectsBatchOver100() {
        List<CampaignEmailProvider.OutgoingEmail> tooBig = java.util.Collections.nCopies(101, email("t@fan.fr"));
        assertThatThrownBy(() -> provider.sendBatch("k", tooBig)).isInstanceOf(IllegalArgumentException.class);
    }

    /** The Art. 50(2) marker reaches the wire only for an AI-written email; a human one or a null disclosure sends none. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"aiSubjectAndBody", "humanWritten", "nullDisclosure"})
    void carriesTheAiDisclosureHeadersOnlyForAiWrittenEmail(String disclosure) throws Exception {
        AiEmailDisclosure ai = switch (disclosure) {
            case "aiSubjectAndBody" -> new AiEmailDisclosure(true, true);
            case "humanWritten" -> AiEmailDisclosure.NONE;
            default -> null;
        };
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(URL)).andExpect(request -> body.set(request.getBody().toString()))
                .andRespond(json(HttpStatus.OK, "{\"data\":[{\"id\":\"m1\"}]}"));

        provider.sendBatch("k", List.of(new CampaignEmailProvider.OutgoingEmail("f <f@news.imin.wtf>", "fan@club.fr",
                "Subject", "<p>h</p>", "t", "https://api.imin.wtf/api/v1/public/unsubscribe/T", ai, null, null)));

        JsonNode headers = JSON.readTree(body.get()).get(0).path("headers");
        if ("aiSubjectAndBody".equals(disclosure)) {
            assertThat(headers.path(AiEmailDisclosure.DISCLOSURE_HEADER).asText()).isEqualTo(AiEmailDisclosure.DISCLOSURE_VALUE);
            assertThat(headers.path(AiEmailDisclosure.PARTS_HEADER).asText()).isEqualTo(new AiEmailDisclosure(true, true).parts());
        } else {
            assertThat(headers.has(AiEmailDisclosure.DISCLOSURE_HEADER)).isFalse();
            assertThat(headers.has(AiEmailDisclosure.PARTS_HEADER)).isFalse();
        }
        assertThat(headers.path("List-Unsubscribe").asText()).isNotBlank();
    }
}
