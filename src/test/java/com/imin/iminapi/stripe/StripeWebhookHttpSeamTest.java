package com.imin.iminapi.stripe;

import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import com.stripe.StripeClient;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the Stripe webhook endpoints: unauthenticated, HMAC-verified over the exact
 * request bytes, and rejected without a side effect when the signature does not hold.
 * Event-type handling is owned by {@link StripeWebhookServiceTest} and {@link SettlementIngestWebhookTest}.
 */
@IminIntegrationTest
class StripeWebhookHttpSeamTest {

    private static final String V1_SECRET = "whsec_seam_v1_secret";
    private static final String CONNECT_SECRET = "whsec_seam_connect_secret";
    private static final String V2_SECRET = "whsec_seam_v2_secret";
    private static final String V1_URL = "/api/v1/stripe/webhook/v1";
    private static final String V2_URL = "/api/v1/stripe/webhook/v2";

    @Autowired MockMvc mvc;
    @Autowired StripeProperties props;
    @Autowired PropertyFlips flips;
    @Autowired StripeClient stripeClient;
    @Autowired JdbcTemplate jdbc;

    private final String eventId = "evt_seam_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void secrets() throws Exception {
        flips.set(props, "webhookSecretV1", V1_SECRET);
        flips.set(props, "webhookSecretConnect", CONNECT_SECRET);
        flips.set(props, "webhookSecretV2", V2_SECRET);
        // The shared StripeClient is a mock; V2 verification is the SDK's own offline HMAC check.
        StripeClient real = new StripeClient("sk_test_seam_offline");
        when(stripeClient.parseEventNotification(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> real.parseEventNotification(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
    }

    // ── V1 ────────────────────────────────────────────────────────────────────

    @Test
    void v1SignedWithTheAccountSecretIsAcceptedWithoutAuthAndRecorded() throws Exception {
        String body = completedEvent(eventId);

        send(V1_URL, body, sign(V1_SECRET, now(), body)).andExpect(status().isOk());

        assertThat(dedupRows(eventId)).isEqualTo(1);
    }

    @Test
    void v1SignedWithTheConnectSecretIsAcceptedThroughTheFallback() throws Exception {
        String body = completedEvent(eventId);

        send(V1_URL, body, sign(CONNECT_SECRET, now(), body)).andExpect(status().isOk());

        assertThat(dedupRows(eventId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownSecret", "garbageHeader", "tamperedBody", "missingHeader", "blankHeader"})
    void v1RejectsAnUnverifiableDeliveryAndRecordsNothing(String variant) throws Exception {
        String signedBody = completedEvent(eventId);
        String body = signedBody;
        String header = sign(V1_SECRET, now(), signedBody);
        switch (variant) {
            case "unknownSecret" -> header = sign("whsec_attacker", now(), signedBody);
            case "garbageHeader" -> header = "t=" + now() + ",v1=deadbeef";
            // One byte changed after signing: the amount the event claims.
            case "tamperedBody" -> body = signedBody.replace("\"amount_total\": 4200", "\"amount_total\": 4201");
            case "missingHeader" -> header = null;
            case "blankHeader" -> header = " ";
            default -> throw new IllegalArgumentException(variant);
        }
        assertThat(body.equals(signedBody)).isEqualTo(!variant.equals("tamperedBody"));

        send(V1_URL, body, header)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(dedupRows(eventId)).isZero();
    }

    @Test
    void v1VerifiesTheRawBytesNotAReserializedBody() throws Exception {
        // Indentation, a trailing newline, non-alphabetical keys and non-ASCII text: any re-encoding changes the HMAC input.
        String body = "{\n"
                + "    \"type\" :  \"checkout.session.completed\",\n"
                + "  \"id\":\"" + eventId + "\",   \"object\": \"event\",\n"
                + "  \"created\": " + now() + ",\n"
                + "  \"data\": {\"object\": {\"object\": \"checkout.session\", \"id\": \"cs_seam\","
                + " \"customer_details\": {\"name\": \"Zoë Ñúñez — Київ\"}}}\n"
                + "}\n";

        send(V1_URL, body, sign(V1_SECRET, now(), body)).andExpect(status().isOk());

        assertThat(dedupRows(eventId)).isEqualTo(1);
    }

    @Test
    void v1RejectsASignatureOlderThanTheToleranceWindow() throws Exception {
        String body = completedEvent(eventId);
        long sixMinutesAgo = now() - 360;

        send(V1_URL, body, sign(V1_SECRET, sixMinutesAgo, body)).andExpect(status().isBadRequest());

        assertThat(dedupRows(eventId)).isZero();
    }

    @Test
    void v1ReplayOfTheSameEventIsAckedAndRecordedOnce() throws Exception {
        String body = completedEvent(eventId);
        String header = sign(V1_SECRET, now(), body);

        send(V1_URL, body, header).andExpect(status().isOk());
        send(V1_URL, body, header).andExpect(status().isOk());

        assertThat(dedupRows(eventId)).isEqualTo(1);
    }

    // ── V2 ────────────────────────────────────────────────────────────────────

    @Test
    void v2SignedWithTheV2SecretIsAcceptedWithoutAuthOverTheRawBody() throws Exception {
        String body = thinEvent(eventId, "imin.seam.unsubscribed");
        String header = sign(V2_SECRET, now(), body);

        send(V2_URL, body, header).andExpect(status().isOk());

        verify(stripeClient).parseEventNotification(eq(body), eq(header), eq(V2_SECRET));
    }

    @Test
    void v2BadSignatureIsRejectedBeforeTheEventIsFetched() throws Exception {
        String body = thinEvent(eventId, "v2.core.account[requirements].updated");

        send(V2_URL, body, sign(V1_SECRET, now(), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        verify(stripeClient, never()).v2();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResultActions send(String url, String body, String sigHeader) throws Exception {
        MockHttpServletRequestBuilder req = post(url)
                .contentType("application/json; charset=utf-8")
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (sigHeader != null) req.header("Stripe-Signature", sigHeader);
        return mvc.perform(req);
    }

    private static String sign(String secret, long timestamp, String body) throws Exception {
        return "t=" + timestamp + ",v1=" + Webhook.Util.computeHmacSha256(secret, timestamp + "." + body);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private int dedupRows(String id) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM processed_webhook_events WHERE stripe_event_id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    /** {@code checkout.session.completed} is a documented no-op, so the dedup row is its only effect. */
    private static String completedEvent(String id) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "checkout.session.completed",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              "data": {
                "object": {
                  "id": "cs_seam_%s",
                  "object": "checkout.session",
                  "amount_total": 4200,
                  "currency": "eur",
                  "metadata": {}
                }
              }
            }
            """.formatted(id, now(), id);
    }

    private static String thinEvent(String id, String type) {
        return """
            {"id":"%s","object":"v2.core.event","type":"%s","created":"2026-10-08T10:00:00.000Z",\
            "livemode":false,"related_object":{"id":"acct_seam","type":"v2.core.account","url":"/v2/core/accounts/acct_seam"}}
            """.formatted(id, type);
    }
}
