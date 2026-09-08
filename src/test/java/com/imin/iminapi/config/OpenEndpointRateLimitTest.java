package com.imin.iminapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.buyer.service.BuyerCredentialService;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.EventContentService;
import com.imin.iminapi.service.auth.AuthService;
import com.imin.iminapi.service.event.FunnelTrackingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The unauthenticated endpoints the 2026-09 legal/security audit found with no
 * rate-limit bucket at all.
 *
 * <p>Mocks {@link RateLimiter} rather than exhausting a real bucket, for the
 * reason {@code BuyerCredentialRateLimitTest} spells out: {@code RateLimitConfig}
 * is {@code @Profile("!test")} and {@link TestRateLimitConfig} invents a
 * 1000/minute bucket for any name, so "it eventually 429s" would pass against an
 * unmetered controller AND against a typo'd bucket name that 500s in production.
 * What is worth pinning is the exact pair each controller asks for — which
 * bucket, and which key. {@code RateLimitBucketCoverageTest} independently proves
 * those names exist in both application.yaml and RateLimitConfig.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class OpenEndpointRateLimitTest {

    /** MockMvc's default remote address, i.e. what getRemoteAddr() returns here. */
    private static final String LOCAL_IP = "ip:127.0.0.1";
    private static final String BUYER_ORIGIN = "http://localhost:3000";

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean AuthService authService;
    @MockitoBean BuyerCredentialService buyerCredentials;
    @MockitoBean EventContentService eventContent;
    @MockitoBean FunnelTrackingService tracking;

    @Test
    void verifyEmail_consumes_the_verify_email_bucket_keyed_per_address() throws Exception {
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                Map.of("email", "Ada@Example.com", "code", "123456"))));
        verify(rateLimiter).consume("verify-email", "ada@example.com");
    }

    @Test
    void signup_consumes_the_signup_bucket_keyed_per_ip() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "email", "ada@example.com",
                                "password", "lovelace12",
                                "firstName", "Ada",
                                "lastName", "Lovelace",
                                "orgName", "Ada Co",
                                "country", "GB"))));
        verify(rateLimiter).consume("signup", LOCAL_IP);
    }

    @Test
    void organizerResetPassword_consumes_its_bucket_keyed_per_ip() throws Exception {
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                Map.of("token", "t", "newPassword", "lovelace12"))))
                .andExpect(status().isOk());
        verify(rateLimiter).consume("reset-password-token", LOCAL_IP);
    }

    @Test
    void buyerResetPassword_consumes_its_own_bucket_keyed_per_ip() throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/reset-password")
                        .header(HttpHeaders.ORIGIN, BUYER_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                Map.of("token", "t", "password", "lovelace123"))))
                .andExpect(status().isNoContent());
        verify(rateLimiter).consume("buyer-reset-password-token", LOCAL_IP);
    }

    @Test
    void aiContent_consumes_the_ai_content_bucket_keyed_per_ip() throws Exception {
        mvc.perform(post("/api/v1/events/ai-content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", "techno night"))));
        verify(rateLimiter).consume("ai-content", LOCAL_IP);
    }

    @Test
    void unsubscribe_consumes_its_bucket_on_both_verbs() throws Exception {
        mvc.perform(post("/api/v1/public/unsubscribe/not-a-real-token"));
        mvc.perform(get("/api/v1/public/unsubscribe/not-a-real-token"));
        verify(rateLimiter, org.mockito.Mockito.times(2)).consume("unsubscribe", LOCAL_IP);
    }

    @Test
    void track_consumes_the_public_track_bucket_keyed_per_ip() throws Exception {
        mvc.perform(post("/api/v1/public/events/" + UUID.randomUUID() + "/track")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent());
        verify(rateLimiter).consume("public-track", LOCAL_IP);
    }

    /**
     * The beacon's always-204 contract is what stops it leaking whether an event
     * exists, so a full bucket must drop the write and still answer 204 — never
     * turn into a 429.
     */
    @Test
    void track_over_limit_drops_the_beacon_and_still_answers_204() throws Exception {
        doThrow(ApiException.rateLimited()).when(rateLimiter).consume("public-track", LOCAL_IP);

        mvc.perform(post("/api/v1/public/events/" + UUID.randomUUID() + "/track")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent());
        verify(tracking, never()).track(any(), any());
    }
}
