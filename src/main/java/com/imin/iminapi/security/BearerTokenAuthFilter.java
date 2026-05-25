package com.imin.iminapi.security;

import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.gate.GateAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves the {@code Authorization: Bearer …} header into a Spring Security
 * principal. Supports two token kinds:
 *
 * <ul>
 *   <li><b>User session tokens</b> (opaque, issued by {@code POST /api/v1/auth/login}) —
 *       resolved against {@link AuthSessionRepository}. The principal carries
 *       the real {@code userId} and {@code orgId}.</li>
 *   <li><b>Gate device tokens</b> (opaque, issued by {@code POST /api/v1/gate/login}) —
 *       resolved via {@link GateAuthService#authenticate(String)}. The
 *       principal carries the org id only ({@code userId == null}, kind = GATE).
 *       Gate tokens are <i>scope-restricted</i>: they are accepted only on
 *       the gate sub-tree ({@code /api/v1/gate/**}) and the single ticket-
 *       redeem endpoint ({@code POST /api/v1/orgs/{orgId}/events/{eventId}/tickets/redeem}).
 *       On any other endpoint the principal is dropped and the request
 *       continues unauthenticated, which lets the {@link com.imin.iminapi.config.SecurityConfig}
 *       authorize-rules return the canonical {@code 401 AUTH_MISSING} envelope.</li>
 * </ul>
 *
 * <p>The two paths run in order — user-session first, then gate — so a stolen
 * gate token cannot impersonate a real user even if hashes collided (which
 * they cannot, but the explicit ordering is defence in depth).
 */
@Component
public class BearerTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthFilter.class);

    /**
     * Whitelist of request URIs a gate token is allowed to authenticate.
     * Anything else: principal is dropped and SecurityConfig returns 401.
     * Kept as constants instead of properties on purpose — this is a security
     * invariant, not a tunable.
     */
    private static final Pattern GATE_ALLOWED_PATH = Pattern.compile(
            "^/api/v1/gate(/.*)?$" +
            "|^/api/v1/orgs/[0-9a-fA-F-]{36}/events/[0-9a-fA-F-]{36}/tickets/redeem$");

    private final AuthSessionRepository sessions;
    private final UserRepository users;
    private final TokenService tokens;
    private final GateAuthService gateAuth;

    public BearerTokenAuthFilter(AuthSessionRepository sessions,
                                  UserRepository users,
                                  TokenService tokens,
                                  GateAuthService gateAuth) {
        this.sessions = sessions;
        this.users = users;
        this.tokens = tokens;
        this.gateAuth = gateAuth;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String raw = header.substring("Bearer ".length()).trim();

        // 1) Try as a user session.
        if (tryUserSession(raw, request)) {
            chain.doFilter(request, response);
            return;
        }

        // 2) Try as a gate token, but only honour it if the request path is in
        //    the gate whitelist. A gate token presented elsewhere is silently
        //    ignored — the request continues unauthenticated, so SecurityConfig
        //    will return AUTH_MISSING. (Not 403: we don't want to confirm "yes,
        //    this is a real gate token, but this endpoint refuses it".)
        Optional<AuthPrincipal> gatePrincipal = gateAuth.authenticate(raw);
        if (gatePrincipal.isPresent() && isGateAllowedPath(request)) {
            populate(gatePrincipal.get());
        }
        chain.doFilter(request, response);
    }

    private boolean tryUserSession(String raw, HttpServletRequest request) {
        String hash = tokens.hashOf(raw);
        Optional<AuthSession> maybe = sessions.findByTokenHashAndRevokedAtIsNull(hash);
        if (maybe.isEmpty()) return false;
        AuthSession s = maybe.get();
        if (s.getExpiresAt().isBefore(Instant.now())) {
            request.setAttribute("imin.authErrorCode", ErrorCode.AUTH_TOKEN_EXPIRED);
            return false;
        }
        Optional<User> user = users.findById(s.getUserId());
        if (user.isEmpty()) return false;
        AuthPrincipal principal = new AuthPrincipal(
                user.get().getId(), user.get().getOrgId(), user.get().getRole(), s.getId());
        populate(principal);
        return true;
    }

    private void populate(AuthPrincipal principal) {
        String roleName = principal.role() == null ? "MEMBER" : principal.role().name();
        AbstractAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + roleName)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static boolean isGateAllowedPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if (uri == null) return false;
        boolean allowed = GATE_ALLOWED_PATH.matcher(uri).matches();
        if (!allowed && log.isDebugEnabled()) {
            log.debug("Gate token rejected on path {}", uri);
        }
        return allowed;
    }

    // Test hook — package-private so tests can poke the path matcher without
    // spinning a real servlet context.
    static boolean isGateAllowedPathForTest(String uri) {
        return uri != null && GATE_ALLOWED_PATH.matcher(uri).matches();
    }
}
