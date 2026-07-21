package com.imin.iminapi.controller.auth;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller tests for the social-sign-in surface. The test profile leaves every
 * {@code imin.oauth.*} secret blank, so both providers are disabled — this
 * exercises the config-gated behavior (buttons hidden, endpoints 404) without any
 * Google/Apple network.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class OAuthControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void providers_reports_both_disabled_when_unconfigured() throws Exception {
        mvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.google").value(false))
                .andExpect(jsonPath("$.apple").value(false));
    }

    @Test
    void google_url_returns_404_when_disabled() throws Exception {
        mvc.perform(get("/api/v1/auth/google/url"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_DISABLED"));
    }

    @Test
    void apple_url_returns_404_when_disabled() throws Exception {
        mvc.perform(get("/api/v1/auth/apple/url"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_DISABLED"));
    }

    @Test
    void google_callback_returns_404_when_disabled() throws Exception {
        mvc.perform(post("/api/v1/auth/google/callback")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc\",\"state\":\"xyz\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_DISABLED"));
    }

    @Test
    void apple_return_redirects_to_login_error_when_disabled() throws Exception {
        mvc.perform(post("/api/v1/auth/apple/return")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("code", "abc")
                        .param("state", "xyz"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://dashboard.imin.wtf/auth/login?oauth_error=1"));
    }

    @Test
    void apple_notifications_always_returns_200() throws Exception {
        mvc.perform(post("/api/v1/auth/apple/notifications")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("payload", ""))
                .andExpect(status().isOk());
    }
}
