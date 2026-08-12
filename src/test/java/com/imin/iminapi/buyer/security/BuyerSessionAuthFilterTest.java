package com.imin.iminapi.buyer.security;

import com.imin.iminapi.buyer.BuyerProperties;
import com.imin.iminapi.buyer.model.BuyerSession;
import com.imin.iminapi.buyer.repository.BuyerSessionRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the buyer cookie filter. The revoked case is covered here by
 * the repository contract (the filter only ever asks for unrevoked rows) and
 * end-to-end against a real database in {@code BuyerSecurityIntegrationTest}.
 */
class BuyerSessionAuthFilterTest {

    BuyerSessionRepository sessions = mock(BuyerSessionRepository.class);
    TokenService tokens = new TokenService();
    BuyerProperties props = new BuyerProperties();
    BuyerSessionAuthFilter filter;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
        filter = new BuyerSessionAuthFilter(sessions, tokens, props);
    }

    @AfterEach
    void teardown() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest buyerRequest(String rawCookie) {
        var req = new MockHttpServletRequest("GET", "/api/v1/buyer/me");
        req.setRequestURI("/api/v1/buyer/me");
        if (rawCookie != null) req.setCookies(new Cookie(BuyerSessionCookie.NAME, rawCookie));
        return req;
    }

    private BuyerSession live(String raw, UUID accountId) {
        BuyerSession s = new BuyerSession();
        s.setId(UUID.randomUUID());
        s.setBuyerAccountId(accountId);
        s.setTokenHash(tokens.hashOf(raw));
        s.setIssuedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        s.setLastUsedAt(Instant.now());
        s.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return s;
    }

    @Test
    void no_cookie_leaves_context_empty() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = buyerRequest(null);
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }

    @Test
    void valid_cookie_populates_buyer_principal_with_role_buyer() throws Exception {
        UUID accountId = UUID.randomUUID();
        String raw = "buyer-token-aaaaaaaaaaaaaaaaaaaaaaaaaaa";
        BuyerSession session = live(raw, accountId);
        when(sessions.findByTokenHashAndRevokedAtIsNull(tokens.hashOf(raw)))
                .thenReturn(Optional.of(session));

        filter.doFilter(buyerRequest(raw), new MockHttpServletResponse(), mock(FilterChain.class));

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(BuyerPrincipal.class);
        BuyerPrincipal p = (BuyerPrincipal) auth.getPrincipal();
        assertThat(p.accountId()).isEqualTo(accountId);
        assertThat(p.sessionId()).isEqualTo(session.getId());
        assertThat(auth.getAuthorities().stream().map(Object::toString).toList())
                .containsExactly("ROLE_BUYER");
    }

    @Test
    void unknown_or_revoked_cookie_does_not_authenticate() throws Exception {
        // The repository query excludes revoked rows, so revoked and unknown are
        // indistinguishable here — deliberately.
        when(sessions.findByTokenHashAndRevokedAtIsNull(any())).thenReturn(Optional.empty());
        filter.doFilter(buyerRequest("nope"), new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void expired_session_does_not_authenticate_and_flags_token_expired() throws Exception {
        String raw = "buyer-token-expired-000000000000000";
        BuyerSession s = live(raw, UUID.randomUUID());
        s.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(sessions.findByTokenHashAndRevokedAtIsNull(tokens.hashOf(raw))).thenReturn(Optional.of(s));

        var req = buyerRequest(raw);
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(req.getAttribute("imin.authErrorCode")).isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    @Test
    void idle_session_beyond_the_idle_window_does_not_authenticate() throws Exception {
        String raw = "buyer-token-idle-1111111111111111111";
        BuyerSession s = live(raw, UUID.randomUUID());
        // Absolute expiry is still in the future; last use is not.
        s.setLastUsedAt(Instant.now().minus(props.getSessionIdleDays() + 1L, ChronoUnit.DAYS));
        when(sessions.findByTokenHashAndRevokedAtIsNull(tokens.hashOf(raw))).thenReturn(Optional.of(s));

        var req = buyerRequest(raw);
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(req.getAttribute("imin.authErrorCode")).isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    @Test
    void cookie_is_ignored_outside_the_buyer_namespace() throws Exception {
        String raw = "buyer-token-scope-2222222222222222";
        when(sessions.findByTokenHashAndRevokedAtIsNull(any()))
                .thenReturn(Optional.of(live(raw, UUID.randomUUID())));

        var req = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        req.setRequestURI("/api/v1/auth/me");
        req.setCookies(new Cookie(BuyerSessionCookie.NAME, raw));
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("a buyer cookie must never authenticate an organizer endpoint")
                .isNull();
        verify(sessions, never()).findByTokenHashAndRevokedAtIsNull(any());
    }

    @Test
    void existing_principal_is_not_overwritten() throws Exception {
        AuthPrincipal organizer = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                com.imin.iminapi.model.UserRole.OWNER, UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(organizer, null, List.of()));

        String raw = "buyer-token-conflict-33333333333333";
        filter.doFilter(buyerRequest(raw), new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isSameAs(organizer);
        verify(sessions, never()).findByTokenHashAndRevokedAtIsNull(any());
    }

    @Test
    void last_used_at_is_refreshed_once_per_day_not_per_request() throws Exception {
        String raw = "buyer-token-touch-4444444444444444";
        BuyerSession stale = live(raw, UUID.randomUUID());
        stale.setLastUsedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        when(sessions.findByTokenHashAndRevokedAtIsNull(tokens.hashOf(raw)))
                .thenReturn(Optional.of(stale));
        filter.doFilter(buyerRequest(raw), new MockHttpServletResponse(), mock(FilterChain.class));
        verify(sessions).touchLastUsed(eq(stale.getId()), any());

        SecurityContextHolder.clearContext();
        BuyerSessionRepository fresh = mock(BuyerSessionRepository.class);
        BuyerSession today = live(raw, UUID.randomUUID());
        when(fresh.findByTokenHashAndRevokedAtIsNull(tokens.hashOf(raw)))
                .thenReturn(Optional.of(today));
        new BuyerSessionAuthFilter(fresh, tokens, props)
                .doFilter(buyerRequest(raw), new MockHttpServletResponse(), mock(FilterChain.class));
        verify(fresh, never()).touchLastUsed(any(), any());
    }

    @Test
    void a_shadowed_cookie_authenticates_nobody() throws Exception {
        // A second cookie of this name can only have come from a Domain=imin.wtf
        // cookie set by another host — XSS or a subdomain takeover. Cookie order
        // is not specified, so the credential must not be guessed at.
        UUID accountId = UUID.randomUUID();
        String real = "buyer-token-real-5555555555555555";
        when(sessions.findByTokenHashAndRevokedAtIsNull(any()))
                .thenReturn(Optional.of(live(real, accountId)));

        var req = new MockHttpServletRequest("GET", "/api/v1/buyer/me");
        req.setRequestURI("/api/v1/buyer/me");
        req.setCookies(new Cookie(BuyerSessionCookie.NAME, "attacker-planted-value"),
                new Cookie(BuyerSessionCookie.NAME, real));
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(sessions, never()).findByTokenHashAndRevokedAtIsNull(any());
        assertThat(req.getAttribute("imin.authErrorCode")).isEqualTo(ErrorCode.AUTH_MISSING);
    }

    @Test
    void buyer_path_matcher_unit() {
        assertThat(BuyerSessionAuthFilter.isBuyerPath("/api/v1/buyer")).isTrue();
        assertThat(BuyerSessionAuthFilter.isBuyerPath("/api/v1/buyer/me")).isTrue();
        assertThat(BuyerSessionAuthFilter.isBuyerPath("/api/v1/buyer/auth/logout")).isTrue();
        assertThat(BuyerSessionAuthFilter.isBuyerPath("/api/v1/buyers")).isFalse();
        assertThat(BuyerSessionAuthFilter.isBuyerPath("/api/v1/public/orders/x")).isFalse();
        assertThat(BuyerSessionAuthFilter.isBuyerPath("/api/v1/auth/me")).isFalse();
        assertThat(BuyerSessionAuthFilter.isBuyerPath(null)).isFalse();
    }
}
