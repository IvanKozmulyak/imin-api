package com.imin.iminapi.controller.auth;

import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.RecordingRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Social sign-in with both providers unconfigured (the test profile leaves every imin.oauth secret blank):
 * the endpoints answer a clean "disabled", and the callbacks are metered before the provider gate.
 */
@IminIntegrationTest
class OAuthControllerTest {

    @Autowired MockMvc mvc;
    @Autowired RecordingRateLimiter rateLimiter;

    private static MockHttpServletRequestBuilder request(String route) {
        return switch (route) {
            case "google-url" -> get("/api/v1/auth/google/url");
            case "apple-url" -> get("/api/v1/auth/apple/url");
            case "google-callback" -> post("/api/v1/auth/google/callback")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"abc\",\"state\":\"xyz\"}");
            case "apple-return" -> post("/api/v1/auth/apple/return")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("code", "abc").param("state", "xyz");
            default -> throw new IllegalArgumentException(route);
        };
    }

    /** The login page hides a button whose provider reports false. */
    @Test
    void providers_reports_both_disabled_when_unconfigured() throws Exception {
        mvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.google").value(false))
                .andExpect(jsonPath("$.apple").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"google-url", "apple-url", "google-callback"})
    void a_disabled_provider_is_a_404(String route) throws Exception {
        mvc.perform(request(route))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_DISABLED"));
    }

    @Test
    void apple_return_on_a_disabled_provider_redirects_to_the_login_error() throws Exception {
        mvc.perform(request("apple-return"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://dashboard.imin.wtf/auth/login?oauth_error=1"));
    }

    /** Both callbacks drive an outbound token POST; metered per IP before the gate, so probing is not free. */
    @ParameterizedTest
    @ValueSource(strings = {"google-callback", "apple-return"})
    void a_callback_is_metered_per_ip_before_the_provider_gate(String route) throws Exception {
        rateLimiter.limit("oauth-callback", 0);

        mvc.perform(request(route))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertThat(rateLimiter.calls()).contains(new RecordingRateLimiter.Call("oauth-callback", "ip:127.0.0.1"));
    }

    /** Apple retries a server-to-server notification it did not see answered with 200. */
    @Test
    void apple_notifications_always_return_200() throws Exception {
        mvc.perform(post("/api/v1/auth/apple/notifications")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("payload", ""))
                .andExpect(status().isOk());
    }
}
