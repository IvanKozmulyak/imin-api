package com.imin.iminapi.buyer;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.oauth.GoogleOAuthService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Native Google sign-in — an OS-issued ID token, no redirect, no nonce cookie.
 *
 * <p>The two properties that matter are the same ones
 * {@code BuyerGoogleSignInTest} asserts for the web flow: an unverified Google
 * email cannot mint or join an address claim, and a buyer sign-in creates zero
 * organizations and zero users.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
// Documents the production gate: nativeEnabled() reads this property, and
// src/test/resources/application.yaml configures no imin.oauth block at all, so
// without a value here the real service would report the lane as unconfigured.
// It is NOT what opens the gate in this test — GoogleOAuthService is a
// @MockitoBean below, so its nativeEnabled() answers Mockito's default false
// whatever the property says, and each test stubs it explicitly.
@org.springframework.test.context.TestPropertySource(
        properties = "imin.oauth.google.native-audience=test-native-audience")
class BuyerNativeGoogleSignInTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired BuyerAccountEmailRepository buyerEmails;
    @MockitoBean EmailService email;
    @MockitoBean GoogleOAuthService google;

    @BeforeEach
    void nativeLaneIsConfigured() {
        when(google.nativeEnabled()).thenReturn(true);
    }

    @Test
    void verifiedIdTokenSignsInAndReturnsASessionToken() throws Exception {
        String address = "g-" + UUID.randomUUID() + "@example.test";
        when(google.verifyNativeIdToken(eq("id-token-ok")))
                .thenReturn(new OAuthUserInfo("google", "sub-" + address, address, true, "Sofiya", "K", "Sofiya K"));

        long orgsBefore = orgs.count();
        long usersBefore = users.count();

        mvc.perform(post("/api/v1/buyer/auth/google/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"id-token-ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.emails[0].email").value(address))
                .andExpect(jsonPath("$.emails[0].verified").value(true));

        assertThat(orgs.count()).isEqualTo(orgsBefore);
        assertThat(users.count()).isEqualTo(usersBefore);
    }

    @Test
    void unverifiedGoogleEmailIsRejected() throws Exception {
        String address = "unverified-" + UUID.randomUUID() + "@example.test";
        when(google.verifyNativeIdToken(eq("id-token-unverified")))
                .thenReturn(new OAuthUserInfo("google", "sub-x", address, false, null, null, null));

        mvc.perform(post("/api/v1/buyer/auth/google/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"id-token-unverified\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OAUTH_EMAIL_UNVERIFIED"));

        // The status alone would pass if the gate ran *after* the claim was
        // written. This is the assertion that actually pins the invariant: an
        // unverified Google address minted no verified claim it could later be
        // used to join.
        assertThat(buyerEmails.findByVerifiedKey(EmailNormalizer.normalize(address))).isEmpty();
    }

    @Test
    void blankIdTokenIs400() throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/google/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
