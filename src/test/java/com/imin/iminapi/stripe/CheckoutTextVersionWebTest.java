package com.imin.iminapi.stripe;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.CheckoutConsent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code marketingOptInTextVersion} on both public checkout endpoints reaches the consent value. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class CheckoutTextVersionWebTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID TIER = UUID.randomUUID();
    private static final String VERSION = "checkout-org-named-2026-09";
    private static final String LABEL = "Email me about Arty Farty's events.";

    @Autowired MockMvc mvc;
    @MockitoBean StripeCheckoutService checkout;
    @MockitoBean StripePaymentIntentService intents;

    private String body(String version) {
        return "{\"tierId\":\"" + TIER + "\",\"quantity\":1,\"marketingOptIn\":true,"
                + "\"marketingOptInProofText\":\"" + LABEL + "\""
                + (version == null ? "" : ",\"marketingOptInTextVersion\":\"" + version + "\"") + "}";
    }

    private CheckoutConsent hostedConsent(String version) throws Exception {
        when(checkout.createCheckout(any(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any()))
                .thenReturn(new StripeCheckoutService.CheckoutResult("stripe", "https://pay", "cs_1", null));
        mvc.perform(post("/api/v1/public/events/" + EVENT + "/checkout")
                        .contentType(MediaType.APPLICATION_JSON).content(body(version)))
                .andExpect(status().isOk());
        ArgumentCaptor<CheckoutConsent> consent = ArgumentCaptor.forClass(CheckoutConsent.class);
        verify(checkout).createCheckout(any(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), any(), any(), consent.capture());
        return consent.getValue();
    }

    @Test
    void hostedCheckout_forwardsTheTextVersionWithTheProof() throws Exception {
        CheckoutConsent consent = hostedConsent(VERSION);

        assertThat(consent.marketingOptInProofText()).isEqualTo(LABEL);
        assertThat(consent.marketingOptInTextVersion()).isEqualTo(VERSION);
    }

    @Test
    void hostedCheckout_withoutTextVersion_leavesItNull() throws Exception {
        assertThat(hostedConsent(null).marketingOptInTextVersion()).isNull();
    }

    @Test
    void nativeCheckout_forwardsTheTextVersionWithTheProof() throws Exception {
        when(intents.create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new StripePaymentIntentService.NativeIntent("pi_x_secret_y", "pi_x", 100L, 10L, "eur"));

        mvc.perform(post("/api/v1/public/events/" + EVENT + "/payment-intent")
                        .contentType(MediaType.APPLICATION_JSON).content(body(VERSION)))
                .andExpect(status().isOk());

        ArgumentCaptor<CheckoutConsent> consent = ArgumentCaptor.forClass(CheckoutConsent.class);
        verify(intents).create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), consent.capture());
        assertThat(consent.getValue().marketingOptInTextVersion()).isEqualTo(VERSION);
    }

    @Test
    void nativeCheckout_textVersionOver32Chars_is400AndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/v1/public/events/" + EVENT + "/payment-intent")
                        .contentType(MediaType.APPLICATION_JSON).content(body("v".repeat(33))))
                .andExpect(status().isBadRequest());

        verify(intents, never()).create(any(), any(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void hostedCheckout_textVersionOver32Chars_is400AndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/v1/public/events/" + EVENT + "/checkout")
                        .contentType(MediaType.APPLICATION_JSON).content(body("v".repeat(33))))
                .andExpect(status().isBadRequest());

        verify(checkout, never()).createCheckout(any(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any());
    }
}
