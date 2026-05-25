package com.imin.iminapi.security;

import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.gate.GateAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BearerTokenAuthFilterTest {

    AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    UserRepository users = mock(UserRepository.class);
    TokenService tokens = new TokenService();
    GateAuthService gateAuth = mock(GateAuthService.class);
    BearerTokenAuthFilter filter;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
        filter = new BearerTokenAuthFilter(sessions, users, tokens, gateAuth);
        // Default — most tests don't care about the gate path; they're user-session focused.
        when(gateAuth.authenticate(any())).thenReturn(Optional.empty());
    }

    @Test
    void no_header_leaves_context_empty() throws Exception {
        var req = new MockHttpServletRequest();
        var resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, resp, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }

    @Test
    void valid_token_populates_principal() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String raw = "abcdef1234567890abcdef1234567890";
        String hash = tokens.hashOf(raw);

        AuthSession s = new AuthSession();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setTokenHash(hash);
        s.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        when(sessions.findByTokenHashAndRevokedAtIsNull(hash)).thenReturn(Optional.of(s));

        User u = new User();
        u.setId(userId);
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        when(users.findById(userId)).thenReturn(Optional.of(u));

        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + raw);
        var resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, resp, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(AuthPrincipal.class);
        AuthPrincipal p = (AuthPrincipal) auth.getPrincipal();
        assertThat(p.userId()).isEqualTo(userId);
        assertThat(p.orgId()).isEqualTo(orgId);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void expired_token_does_not_authenticate() throws Exception {
        String raw = "expiredtoken000000000000000000000";
        String hash = tokens.hashOf(raw);
        AuthSession s = new AuthSession();
        s.setId(UUID.randomUUID());
        s.setUserId(UUID.randomUUID());
        s.setTokenHash(hash);
        s.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(sessions.findByTokenHashAndRevokedAtIsNull(hash)).thenReturn(Optional.of(s));

        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + raw);
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unknown_token_does_not_authenticate() throws Exception {
        when(sessions.findByTokenHashAndRevokedAtIsNull(any())).thenReturn(Optional.empty());
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer whatever");
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── Gate-token tests ───────────────────────────────────────────────────

    @Test
    void gate_token_populates_gate_principal_on_redeem_path() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String raw = "gate-token-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        when(sessions.findByTokenHashAndRevokedAtIsNull(tokens.hashOf(raw)))
                .thenReturn(Optional.empty()); // not a user session
        when(gateAuth.authenticate(raw))
                .thenReturn(Optional.of(AuthPrincipal.forGate(orgId, sessionId)));

        var req = new MockHttpServletRequest("POST",
                "/api/v1/orgs/" + orgId + "/events/" + UUID.randomUUID() + "/tickets/redeem");
        req.setRequestURI(req.getRequestURI()); // ensure URI is set
        req.addHeader("Authorization", "Bearer " + raw);
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        AuthPrincipal p = (AuthPrincipal) auth.getPrincipal();
        assertThat(p.isGate()).isTrue();
        assertThat(p.orgId()).isEqualTo(orgId);
        assertThat(p.userId()).isNull();
    }

    @Test
    void gate_token_populates_gate_principal_on_gate_subtree() throws Exception {
        UUID orgId = UUID.randomUUID();
        String raw = "gate-token-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        when(sessions.findByTokenHashAndRevokedAtIsNull(any())).thenReturn(Optional.empty());
        when(gateAuth.authenticate(raw))
                .thenReturn(Optional.of(AuthPrincipal.forGate(orgId, UUID.randomUUID())));

        var req = new MockHttpServletRequest("GET", "/api/v1/gate/me");
        req.setRequestURI("/api/v1/gate/me");
        req.addHeader("Authorization", "Bearer " + raw);
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void gate_token_is_silently_dropped_outside_whitelisted_paths() throws Exception {
        UUID orgId = UUID.randomUUID();
        String raw = "gate-token-cccccccccccccccccccccccccccccc";

        when(sessions.findByTokenHashAndRevokedAtIsNull(any())).thenReturn(Optional.empty());
        when(gateAuth.authenticate(raw))
                .thenReturn(Optional.of(AuthPrincipal.forGate(orgId, UUID.randomUUID())));

        // /api/v1/org is the organizer dashboard — gate token MUST NOT authenticate here.
        var req = new MockHttpServletRequest("GET", "/api/v1/org");
        req.setRequestURI("/api/v1/org");
        req.addHeader("Authorization", "Bearer " + raw);
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("gate token must not authenticate on /api/v1/org")
                .isNull();
    }

    @Test
    void gate_token_is_silently_dropped_on_other_org_subtree() throws Exception {
        UUID orgId = UUID.randomUUID();
        String raw = "gate-token-dddddddddddddddddddddddddddddd";
        when(sessions.findByTokenHashAndRevokedAtIsNull(any())).thenReturn(Optional.empty());
        when(gateAuth.authenticate(raw))
                .thenReturn(Optional.of(AuthPrincipal.forGate(orgId, UUID.randomUUID())));

        // Org-scoped path that is NOT the redeem endpoint — e.g. listing events.
        // Gate tokens must not be honoured here even though the org id matches.
        String otherPath = "/api/v1/orgs/" + orgId + "/stripe/status";
        var req = new MockHttpServletRequest("GET", otherPath);
        req.setRequestURI(otherPath);
        req.addHeader("Authorization", "Bearer " + raw);
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void gate_path_matcher_unit() {
        UUID orgId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertThat(BearerTokenAuthFilter.isGateAllowedPathForTest("/api/v1/gate/login")).isTrue();
        assertThat(BearerTokenAuthFilter.isGateAllowedPathForTest("/api/v1/gate")).isTrue();
        assertThat(BearerTokenAuthFilter.isGateAllowedPathForTest("/api/v1/gate/whatever")).isTrue();
        assertThat(BearerTokenAuthFilter.isGateAllowedPathForTest(
                "/api/v1/orgs/" + orgId + "/events/" + eventId + "/tickets/redeem")).isTrue();
        // Not allowed
        assertThat(BearerTokenAuthFilter.isGateAllowedPathForTest("/api/v1/org")).isFalse();
        assertThat(BearerTokenAuthFilter.isGateAllowedPathForTest("/api/v1/events")).isFalse();
        assertThat(BearerTokenAuthFilter.isGateAllowedPathForTest(
                "/api/v1/orgs/" + orgId + "/stripe/status")).isFalse();
        // Redeem path with malformed orgId/eventId must not match
        assertThat(BearerTokenAuthFilter.isGateAllowedPathForTest(
                "/api/v1/orgs/not-a-uuid/events/" + eventId + "/tickets/redeem")).isFalse();
        // Trailing-slash variants
        assertThat(BearerTokenAuthFilter.isGateAllowedPathForTest(
                "/api/v1/orgs/" + orgId + "/events/" + eventId + "/tickets/redeem/")).isFalse();
    }

    @Test
    void expired_token_returns_AUTH_TOKEN_EXPIRED() throws Exception {
        String raw = "expiredtokenforexpiredtest00000000";
        String hash = tokens.hashOf(raw);
        AuthSession s = new AuthSession();
        s.setId(UUID.randomUUID());
        s.setUserId(UUID.randomUUID());
        s.setTokenHash(hash);
        s.setExpiresAt(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        when(sessions.findByTokenHashAndRevokedAtIsNull(hash)).thenReturn(Optional.of(s));

        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + raw);
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(req.getAttribute("imin.authErrorCode"))
                .isEqualTo(com.imin.iminapi.security.ErrorCode.AUTH_TOKEN_EXPIRED);
    }
}
