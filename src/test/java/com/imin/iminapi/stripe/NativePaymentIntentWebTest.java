package com.imin.iminapi.stripe;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP wiring only — the endpoint is reachable unauthenticated, it serializes the
 * five NativeIntent fields, it never caches a client secret, and {@code @Valid}
 * is actually on the body parameter.
 *
 * <p>The money assertions deliberately live in {@link NativePaymentIntentTest}
 * against the captured {@code PaymentIntentCreateParams}; the service is mocked
 * here, so anything asserted about amounts would only be re-asserting this
 * test's own stub.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class NativePaymentIntentWebTest {

    @Autowired MockMvc mvc;
    @MockitoBean StripePaymentIntentService intents;

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID TIER = UUID.randomUUID();

    @Test
    void returnsTheClientSecretShapeAndNeverCachesIt() throws Exception {
        when(intents.create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(new StripePaymentIntentService.NativeIntent(
                        "pi_x_secret_y", "pi_x", 5448L, 448L, "eur"));

        mvc.perform(post("/api/v1/public/events/" + EVENT + "/payment-intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":\"" + TIER + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").value("pi_x_secret_y"))
                .andExpect(jsonPath("$.paymentIntentId").value("pi_x"))
                .andExpect(jsonPath("$.amountMinor").value(5448))
                .andExpect(jsonPath("$.feeMinor").value(448))
                .andExpect(jsonPath("$.currency").value("eur"))
                // A client secret is a bearer credential for one payment. It must
                // never land in a shared cache.
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    void missingTierIdIs400() throws Exception {
        mvc.perform(post("/api/v1/public/events/" + EVENT + "/payment-intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").exists());
    }

    /**
     * Proves {@code @Valid} is on the parameter. Without it Spring does not cascade
     * into the record and a quantity of 11 reaches the service, taking an
     * out-of-policy inventory hold before anything rejects it.
     */
    @Test
    void quantityAboveTheCapIs400AndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/v1/public/events/" + EVENT + "/payment-intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":\"" + TIER + "\",\"quantity\":11}"))
                .andExpect(status().isBadRequest());

        verify(intents, never()).create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any());
    }

    /**
     * The {@code Idempotency-Key} header reaches the service. Bound but dropped
     * is the failure mode that matters: the endpoint would keep answering 200
     * while every native retry took a second 30-minute hold on real inventory,
     * and nothing on the wire would say so.
     */
    @Test
    void theIdempotencyKeyHeaderIsForwarded() throws Exception {
        when(intents.create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(new StripePaymentIntentService.NativeIntent(
                        "pi_x_secret_y", "pi_x", 5448L, 448L, "eur"));

        mvc.perform(post("/api/v1/public/events/" + EVENT + "/payment-intent")
                        .header("Idempotency-Key", "retry-me-once")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":\"" + TIER + "\",\"quantity\":2}"))
                .andExpect(status().isOk());

        verify(intents).create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(),
                org.mockito.ArgumentMatchers.eq("retry-me-once"));
    }

    /** The web sends no header at all and must keep working unchanged. */
    @Test
    void noIdempotencyKeyIsStillAValidRequest() throws Exception {
        when(intents.create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(new StripePaymentIntentService.NativeIntent(
                        "pi_x_secret_y", "pi_x", 5448L, 448L, "eur"));

        mvc.perform(post("/api/v1/public/events/" + EVENT + "/payment-intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":\"" + TIER + "\",\"quantity\":2}"))
                .andExpect(status().isOk());

        verify(intents).create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(),
                org.mockito.ArgumentMatchers.isNull());
    }
}
