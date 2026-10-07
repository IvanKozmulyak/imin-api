package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.security.BuyerOAuthNonceCookie;
import com.imin.iminapi.oauth.GoogleOAuthService;
import com.imin.iminapi.oauth.OAuthProperties;
import com.imin.iminapi.oauth.OAuthStateService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code state} audience check, <b>in both directions</b>, through the real
 * endpoints with both Google flows configured.
 *
 * <p>The dangerous direction is the one an earlier draft of the epic did not
 * test: a buyer's {@code code} + {@code state} POSTed to the <b>existing
 * organizer callback</b>. Accepted there, it reaches
 * {@code OAuthAccountService.resolve} step 5, which auto-provisions an
 * {@code Organization} and an {@code OWNER} user from a buyer's Google account —
 * and the sign-in appears to succeed, so nothing surfaces it. Hence the
 * assertions on the rows a provisioning would write, rather than a bare status check.
 *
 * <p>State verification must happen <b>before</b> the token exchange, so every
 * callback test asserts the exchange was never called. The exchange is still
 * stubbed to name a fresh address, so a regression that reached it shows up as rows.
 */
@IminIntegrationTest
class BuyerOAuthAudienceTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String ORGANIZER_REDIRECT = "https://dashboard.imin.wtf/auth/callback/google";
    private static final String BUYER_REDIRECT = "https://app.imin.wtf/auth/callback/google";

    @Autowired MockMvc mvc;
    @Autowired OAuthStateService states;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired GoogleOAuthService google;
    @Autowired OAuthProperties oauthProps;
    @Autowired PropertyFlips flips;

    /** What Google would answer, had the exchange been reached. */
    private String victim;

    /** Both Google flows configured; state-secret is captured at startup and not flipped. */
    @BeforeEach
    void bothGoogleFlowsConfigured() {
        flips.set(oauthProps, "google.clientId", "test-client-id");
        flips.set(oauthProps, "google.clientSecret", "test-client-secret");
        flips.set(oauthProps, "google.redirectUri", ORGANIZER_REDIRECT);
        flips.set(oauthProps, "google.buyerRedirectUri", BUYER_REDIRECT);
        victim = "victim-" + UUID.randomUUID() + "@example.com";
        doReturn(new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(), victim, true,
                "Ada", "Lovelace", "Ada Lovelace")).when(google).exchangeCode(anyString(), anyString());
    }

    // ── The authorize URL and its browser binding ──────────────────────────

    @Test
    void the_buyer_authorize_url_uses_the_buyer_redirect_uri_and_sets_the_nonce_cookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/buyer/auth/google/url"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(queryParam(result, "redirect_uri")).isEqualTo(BUYER_REDIRECT);

        Cookie nonce = result.getResponse().getCookie(BuyerOAuthNonceCookie.NAME);
        assertThat(nonce).isNotNull();
        assertThat(nonce.getValue()).isNotBlank();
        assertThat(nonce.isHttpOnly()).isTrue();
        assertThat(nonce.getSecure()).isTrue();
        assertThat(nonce.getPath()).isEqualTo(BuyerOAuthNonceCookie.PATH);
        assertThat(nonce.getMaxAge()).isEqualTo((int) BuyerOAuthNonceCookie.MAX_AGE.toSeconds());
    }

    @Test
    void the_organizer_authorize_url_still_uses_the_organizer_redirect_uri() throws Exception {
        // The one change this epic makes to live organizer sign-in, asserted at
        // the endpoint as well as in GoogleOAuthServiceTest.
        MvcResult result = mvc.perform(get("/api/v1/auth/google/url"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(queryParam(result, "redirect_uri")).isEqualTo(ORGANIZER_REDIRECT);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("app.imin.wtf");
    }

    // ── Direction 1: buyer state → organizer callback (the dangerous one) ──

    @Test
    void a_buyer_state_posted_to_the_organizer_callback_is_rejected_and_provisions_nothing() throws Exception {
        String buyerState = states.sign("google", OAuthStateService.AUDIENCE_BUYER, "some-browser-nonce");

        mvc.perform(post("/api/v1/auth/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"stolen-code\",\"state\":\"" + buyerState + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("OAUTH_INVALID_STATE"));
        verify(google, never()).exchangeCode(anyString(), anyString());

        assertThat(jdbc.queryForObject("select count(*) from organizations where lower(contact_email) = ?",
                Integer.class, victim))
                .as("OAuthAccountService.resolve step 5 must never be reached from a buyer state")
                .isZero();
        assertThat(users.existsByEmailLower(victim)).isFalse();
    }

    // ── Direction 2: organizer state → buyer callback ──────────────────────

    @Test
    void an_organizer_state_posted_to_the_buyer_callback_is_rejected_and_creates_no_buyer_account()
            throws Exception {
        String organizerState = states.sign("google");

        mvc.perform(post("/api/v1/buyer/auth/google/callback")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(new Cookie(BuyerOAuthNonceCookie.NAME, "some-browser-nonce"))
                        .content("{\"code\":\"stolen-code\",\"state\":\"" + organizerState + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("OAUTH_INVALID_STATE"));
        verify(google, never()).exchangeCode(anyString(), anyString());

        assertThat(jdbc.queryForObject("select count(*) from buyer_account_emails where email_normalized = ?",
                Integer.class, victim)).isZero();
    }

    // ── The browser binding (login CSRF) ───────────────────────────────────

    @Test
    void a_buyer_callback_without_the_nonce_cookie_is_rejected() throws Exception {
        // The login-CSRF shape: the attacker completes a Google authorization
        // and hands the victim the callback URL. The victim's browser never
        // asked for the authorize URL, so it holds no nonce.
        MvcResult authorize = mvc.perform(get("/api/v1/buyer/auth/google/url")).andReturn();
        String state = stateOf(authorize);

        mvc.perform(post("/api/v1/buyer/auth/google/callback")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"attacker-code\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("OAUTH_INVALID_STATE"));
        verify(google, never()).exchangeCode(anyString(), anyString());
    }

    @Test
    void a_buyer_callback_with_another_browsers_nonce_is_rejected() throws Exception {
        MvcResult attackerFlow = mvc.perform(get("/api/v1/buyer/auth/google/url")).andReturn();
        MvcResult victimFlow = mvc.perform(get("/api/v1/buyer/auth/google/url")).andReturn();

        mvc.perform(post("/api/v1/buyer/auth/google/callback")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(victimFlow.getResponse().getCookie(BuyerOAuthNonceCookie.NAME))
                        .content("{\"code\":\"attacker-code\",\"state\":\"" + stateOf(attackerFlow) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("OAUTH_INVALID_STATE"));
        verify(google, never()).exchangeCode(anyString(), anyString());
    }

    /** Pulls the signed state back out of the authorize URL the endpoint returned. */
    private static String stateOf(MvcResult result) throws Exception {
        return queryParam(result, "state");
    }

    /** One decoded query parameter of the authorize URL inside the JSON body. */
    private static String queryParam(MvcResult result, String name) throws Exception {
        String url = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("url").asText();
        String raw = org.springframework.web.util.UriComponentsBuilder.fromUriString(url)
                .build().getQueryParams().getFirst(name);
        return raw == null ? null : URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
