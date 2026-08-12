package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerSession;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerSessionRepository;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.buyer.service.BuyerSessionService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end security tests for {@code /api/v1/buyer/**}: the cookie filter
 * against a real database, the {@code hasRole("BUYER")} gate, the buyer-scoped
 * CORS registration, and the CSRF mitigations that make the platform-wide
 * {@code csrf.disable()} survivable (buyer-accounts epic §2.6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerSecurityIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final String FOREIGN_ORIGIN = "https://imin-public-attacker.vercel.app";

    @Autowired MockMvc mvc;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository emails;
    @Autowired BuyerSessionRepository sessions;
    @Autowired BuyerSessionService sessionService;

    private BuyerAccount account;
    private String primaryEmail;

    @BeforeEach
    void seed() {
        account = new BuyerAccount();
        account.setDisplayName("Ada");
        account.setLocale("en");
        account.setActivatedAt(Instant.now());
        account = accounts.save(account);

        // Unique per test: uq_bae_verified_email is a real platform-wide UNIQUE
        // on verified addresses, and these tests share one database.
        primaryEmail = "Ada+" + UUID.randomUUID() + "@Example.com";
        BuyerAccountEmail primary = BuyerAccountEmail.of(
                account.getId(), primaryEmail, BuyerAccountEmail.ADDED_VIA_SIGNUP);
        primary.markVerified(Instant.now());
        primary.makePrimary();
        emails.save(primary);
    }

    private String issueSession() {
        return sessionService.issue(account.getId(), "JUnit/1.0").rawToken();
    }

    private static Cookie cookie(String raw) {
        return new Cookie(BuyerSessionCookie.NAME, raw);
    }

    // ── The cookie filter, end to end ──────────────────────────────────────

    @Test
    void me_without_a_cookie_is_401() throws Exception {
        mvc.perform(get("/api/v1/buyer/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
    }

    @Test
    void me_with_a_valid_cookie_returns_the_account() throws Exception {
        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(issueSession())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.id").value(account.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Ada"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.emails[0].email").value(primaryEmail))
                .andExpect(jsonPath("$.emails[0].verified").value(true))
                .andExpect(jsonPath("$.emails[0].primary").value(true));
    }

    @Test
    void me_with_an_expired_cookie_is_401() throws Exception {
        String raw = issueSession();
        BuyerSession session = sessions.findAll().stream()
                .filter(s -> s.getBuyerAccountId().equals(account.getId()))
                .findFirst().orElseThrow();
        session.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        sessions.save(session);

        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(raw)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_EXPIRED"));
    }

    @Test
    void me_with_a_revoked_cookie_is_401() throws Exception {
        String raw = issueSession();
        sessionService.revokeAll(account.getId());

        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(raw)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
    }

    @Test
    void a_buyer_cookie_does_not_authenticate_an_organizer_endpoint() throws Exception {
        mvc.perform(get("/api/v1/auth/me").cookie(cookie(issueSession())))
                .andExpect(status().isUnauthorized());
    }

    // ── hasRole("BUYER") actually gates ────────────────────────────────────

    @Test
    void an_organizer_principal_cannot_read_buyer_data() throws Exception {
        Authentication organizer = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.OWNER, UUID.randomUUID()),
                null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));

        mvc.perform(get("/api/v1/buyer/me").with(authentication(organizer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── Logout and revoke-all ──────────────────────────────────────────────

    @Test
    void logout_revokes_the_session_and_clears_the_cookie() throws Exception {
        String raw = issueSession();

        mvc.perform(post("/api/v1/buyer/auth/logout")
                        .cookie(cookie(raw))
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString(BuyerSessionCookie.NAME + "=;")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")));

        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(raw)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_is_204_even_without_a_cookie() throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/logout").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isNoContent());
    }

    @Test
    void revoke_all_kills_every_session_including_the_acting_one() throws Exception {
        String first = issueSession();
        String second = issueSession();

        mvc.perform(post("/api/v1/buyer/sessions/revoke-all")
                        .cookie(cookie(first))
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isNoContent());

        assertThat(sessions.findByBuyerAccountIdAndRevokedAtIsNull(account.getId())).isEmpty();
        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(first)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(second)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revoke_all_requires_a_buyer_session() throws Exception {
        mvc.perform(post("/api/v1/buyer/sessions/revoke-all").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    // ── CSRF: Origin + content type (§2.6) ─────────────────────────────────

    @Test
    void a_cross_origin_form_encoded_post_fails() throws Exception {
        // The shape a plain HTML form on an attacker's page produces. Two
        // independent layers refuse it — the buyer-scoped CORS mapping rejects
        // the foreign origin first, and BuyerRequestGuardFilter would reject it
        // on both Origin and content type (asserted directly in
        // BuyerRequestGuardFilterTest, where no CORS filter is in the way).
        String raw = issueSession();
        mvc.perform(post("/api/v1/buyer/sessions/revoke-all")
                        .cookie(cookie(raw))
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("x=1"))
                .andExpect(status().isForbidden());

        assertThat(sessions.findByBuyerAccountIdAndRevokedAtIsNull(account.getId()))
                .as("a rejected cross-site request must not have changed anything")
                .hasSize(1);
    }

    @Test
    void a_post_with_a_foreign_origin_fails() throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/logout")
                        .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_post_with_no_origin_fails() throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/logout"))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_same_origin_form_encoded_post_is_rejected_on_content_type() throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/logout")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("x=1"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void a_same_origin_multipart_post_is_rejected_on_content_type() throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/logout")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .content("--x--"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void a_get_needs_no_origin() throws Exception {
        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(issueSession())))
                .andExpect(status().isOk());
    }

    // ── Buyer-scoped CORS (§2.6): exact origins, no wildcard patterns ──────

    @Test
    void preflight_from_the_buyer_site_is_allowed() throws Exception {
        mvc.perform(options("/api/v1/buyer/me")
                        .header(HttpHeaders.ORIGIN, "https://app.imin.wtf")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.imin.wtf"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void preflight_from_a_wildcard_vercel_origin_is_refused_on_the_buyer_namespace() throws Exception {
        // The platform-wide list allows https://imin-public-*.vercel.app with
        // credentials. The buyer namespace must not inherit that.
        mvc.perform(options("/api/v1/buyer/me")
                        .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }
}
