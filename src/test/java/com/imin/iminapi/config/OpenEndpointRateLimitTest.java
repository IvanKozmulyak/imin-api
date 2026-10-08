package com.imin.iminapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.EventContentResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.RecordingRateLimiter;
import com.imin.iminapi.support.RecordingRateLimiter.Call;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The unauthenticated endpoints the 2026-09 legal/security audit found with no rate-limit bucket at all.
 * What is pinned is the exact (bucket, key) pair each controller asks for, over the real services;
 * {@code RateLimitBucketCoverageTest} proves those names exist in application.yaml and RateLimitConfig.
 */
@IminIntegrationTest
class OpenEndpointRateLimitTest {

    /** MockMvc's default remote address, i.e. what getRemoteAddr() returns here. */
    private static final String LOCAL_IP = "ip:127.0.0.1";
    private static final String BUYER_ORIGIN = "http://localhost:3000";

    @Autowired MockMvc mvc;
    @Autowired RecordingRateLimiter limiter;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatClient chatClient;
    @Autowired Clock clock;
    final ObjectMapper om = new ObjectMapper();

    @Test
    void verifyEmail_consumes_the_verify_email_bucket_keyed_per_address() throws Exception {
        String email = fx.email("ada");
        mvc.perform(post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("email", email.toUpperCase(), "code", "123456"))));
        assertThat(limiter.calls()).contains(new Call("verify-email", email));
    }

    @Test
    void signup_consumes_the_signup_bucket_keyed_per_ip() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "email", fx.email("ada"),
                        "password", "lovelace12",
                        "firstName", "Ada",
                        "lastName", "Lovelace",
                        "orgName", "Ada Co",
                        "country", "GB"))));
        assertThat(limiter.calls()).contains(new Call("signup", LOCAL_IP));
    }

    @Test
    void organizerResetPassword_consumes_its_bucket_keyed_per_ip() throws Exception {
        mvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("token", "t", "newPassword", "lovelace12"))));
        assertThat(limiter.calls()).contains(new Call("reset-password-token", LOCAL_IP));
    }

    @Test
    void buyerResetPassword_consumes_its_own_bucket_keyed_per_ip() throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/reset-password")
                .header(HttpHeaders.ORIGIN, BUYER_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("token", "t", "password", "lovelace123"))));
        assertThat(limiter.calls()).contains(new Call("buyer-reset-password-token", LOCAL_IP));
    }

    /** An anonymous caller gets the generated content, and the call spent the ai-content bucket. */
    @Test
    void aiContent_consumes_the_ai_content_bucket_keyed_per_ip() throws Exception {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.entity(EventContentResponse.class)).thenReturn(new EventContentResponse(
                List.of("Night Shift"), List.of(), "techno", null, null, null, null, null, null, null, null,
                null, null, List.of()));

        mvc.perform(post("/api/v1/events/ai-content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", "techno night"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names[0]").value("Night Shift"));
        assertThat(limiter.calls()).contains(new Call("ai-content", LOCAL_IP));
    }

    /**
     * The bucket caps how OFTEN an anonymous caller can bill us; the prompt is bounded too, so the paid
     * LLM is never reached with an oversized one.
     */
    @Test
    void aiContent_rejects_an_unbounded_prompt_before_spending_the_bucket() throws Exception {
        mvc.perform(post("/api/v1/events/ai-content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", "x".repeat(5000)))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(chatClient);
        assertThat(limiter.calls()).doesNotContain(new Call("ai-content", LOCAL_IP));
    }

    /**
     * The promo-quote endpoint answers with a distinct reason per promo-code failure, so an unmetered one
     * is a free promo-code oracle. It is the only public POST the 2026-09 bucket sweep missed.
     */
    @Test
    void quote_consumes_the_quote_bucket_keyed_per_ip() throws Exception {
        mvc.perform(post("/api/v1/public/events/" + UUID.randomUUID() + "/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertThat(limiter.calls()).contains(new Call("quote", LOCAL_IP));
    }

    /** A full bucket stops the promo lookup: run first, the lookup would answer 400 for this request, not 429. */
    @Test
    void quote_over_limit_never_reaches_the_service() throws Exception {
        limiter.limit("quote", 0);

        mvc.perform(post("/api/v1/public/events/" + UUID.randomUUID() + "/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void unsubscribe_consumes_its_bucket_on_both_verbs() throws Exception {
        mvc.perform(post("/api/v1/public/unsubscribe/not-a-real-token"));
        mvc.perform(get("/api/v1/public/unsubscribe/not-a-real-token"));
        assertThat(limiter.calls()).filteredOn(c -> c.bucket().equals("unsubscribe"))
                .containsExactly(new Call("unsubscribe", LOCAL_IP), new Call("unsubscribe", LOCAL_IP));
    }

    /** Under the limit the beacon lands, which is what makes the over-limit case below meaningful. */
    @Test
    void track_consumes_the_public_track_bucket_keyed_per_ip() throws Exception {
        Event event = publicEvent();

        mvc.perform(post("/api/v1/public/events/" + event.getId() + "/track")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(beacon()))
                .andExpect(status().isNoContent());

        assertThat(limiter.calls()).contains(new Call("public-track", LOCAL_IP));
        assertThat(funnelRows(event)).isEqualTo(1);
    }

    /**
     * The beacon's always-204 contract is what stops it leaking whether an event exists, so a full bucket
     * drops the write and still answers 204, never a 429.
     */
    @Test
    void track_over_limit_drops_the_beacon_and_still_answers_204() throws Exception {
        Event event = publicEvent();
        limiter.limit("public-track", 0);

        mvc.perform(post("/api/v1/public/events/" + event.getId() + "/track")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(beacon()))
                .andExpect(status().isNoContent());

        assertThat(funnelRows(event)).isZero();
    }

    private Event publicEvent() {
        var org = fx.org();
        return fx.event(org, fx.owner(org), EventStatus.LIVE, clock.instant().plus(Duration.ofDays(7)));
    }

    private String beacon() throws Exception {
        return om.writeValueAsString(Map.of("stage", "PAGE_VIEW", "anonId", "anon-" + UUID.randomUUID()));
    }

    private int funnelRows(Event event) {
        return jdbc.queryForObject("select count(*) from event_funnel_events where event_id = ?",
                Integer.class, event.getId());
    }
}
