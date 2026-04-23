package com.imin.iminapi.security;

import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.UserRepository;
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
    BearerTokenAuthFilter filter;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
        filter = new BearerTokenAuthFilter(sessions, users, tokens);
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
}
