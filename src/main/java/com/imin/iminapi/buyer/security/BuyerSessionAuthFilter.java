package com.imin.iminapi.buyer.security;

import com.imin.iminapi.buyer.BuyerProperties;
import com.imin.iminapi.buyer.model.BuyerSession;
import com.imin.iminapi.buyer.repository.BuyerSessionRepository;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves the {@code imin_buyer_session} cookie into a {@link BuyerPrincipal}
 * with the single authority {@code ROLE_BUYER}.
 *
 * <p>Three deliberate restrictions, each mirroring a precedent in
 * {@link com.imin.iminapi.security.BearerTokenAuthFilter}:
 *
 * <ul>
 *   <li><b>Path-scoped.</b> The cookie is only honoured on
 *       {@code /api/v1/buyer/**}. Its {@code Path} attribute already stops a
 *       browser sending it elsewhere, but a hand-crafted request can send any
 *       cookie anywhere — the same reasoning that scope-restricts gate tokens.
 *       Elsewhere the credential is dropped silently and the request continues
 *       unauthenticated.</li>
 *   <li><b>Never overrides an existing principal.</b> It runs after the bearer
 *       filter and no-ops when something already authenticated, so an organizer
 *       token and a buyer cookie on one request cannot fight.</li>
 *   <li><b>Refuses shadowed cookies.</b> Two cookies of this name means one was
 *       planted with {@code Domain=imin.wtf} from another host; the request
 *       authenticates as nobody instead of as whichever one the container
 *       happened to list first.</li>
 *   <li><b>Rejects expired, idle and revoked sessions.</b> Absolute expiry is
 *       {@code expires_at}; idle expiry is {@code last_used_at} older than
 *       {@code imin.buyer.session-idle-days}; revocation is
 *       {@code revoked_at IS NOT NULL} and is enforced in the query itself.</li>
 * </ul>
 *
 * <p>{@code last_used_at} is refreshed at most once per calendar day (UTC), so
 * a signed-in buyer costs one extra write per day rather than one per request.
 */
@Component
public class BuyerSessionAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BuyerSessionAuthFilter.class);

    /**
     * The only sub-tree where the buyer cookie authenticates. A security
     * invariant, not a tunable — kept as a constant for the same reason
     * {@code BearerTokenAuthFilter.GATE_ALLOWED_PATH} is.
     */
    private static final Pattern BUYER_PATH = Pattern.compile("^/api/v1/buyer(/.*)?$");

    private final BuyerSessionRepository sessions;
    private final TokenService tokens;
    private final BuyerProperties props;

    public BuyerSessionAuthFilter(BuyerSessionRepository sessions,
                                  TokenService tokens,
                                  BuyerProperties props) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }
        if (!isBuyerPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        if (BuyerSessionCookie.isShadowed(request)) {
            // Two cookies of this name: one of them was set with Domain=imin.wtf
            // by some other host under the registrable domain. Which one
            // getCookies() returns first is not specified, so authenticate
            // nobody rather than authenticate whoever won the ordering.
            // See BuyerSessionCookie's Javadoc for why not __Host-.
            log.warn("[buyer] refused a request carrying duplicate {} cookies", BuyerSessionCookie.NAME);
            request.setAttribute("imin.authErrorCode", ErrorCode.AUTH_MISSING);
            chain.doFilter(request, response);
            return;
        }
        String raw = BuyerSessionCookie.read(request);
        if (raw == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<BuyerSession> maybe = sessions.findByTokenHashAndRevokedAtIsNull(tokens.hashOf(raw));
        if (maybe.isEmpty()) {
            // Unknown or revoked — indistinguishable on purpose.
            chain.doFilter(request, response);
            return;
        }

        BuyerSession session = maybe.get();
        Instant now = Instant.now();
        if (isExpired(session, now)) {
            request.setAttribute("imin.authErrorCode", ErrorCode.AUTH_TOKEN_EXPIRED);
            chain.doFilter(request, response);
            return;
        }

        populate(new BuyerPrincipal(session.getBuyerAccountId(), session.getId()));
        touchIfStale(session, now);
        chain.doFilter(request, response);
    }

    /** Absolute expiry, then idle expiry against {@code last_used_at}. */
    private boolean isExpired(BuyerSession session, Instant now) {
        if (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(now)) return true;
        Instant idleCutoff = now.minus(Duration.ofDays(props.getSessionIdleDays()));
        return session.getLastUsedAt() == null || session.getLastUsedAt().isBefore(idleCutoff);
    }

    /**
     * One targeted UPDATE per session per UTC day. Failures are swallowed:
     * this is bookkeeping, and a hiccup on it must never fail an otherwise
     * valid authenticated request.
     */
    private void touchIfStale(BuyerSession session, Instant now) {
        Instant startOfDay = now.truncatedTo(ChronoUnit.DAYS);
        if (session.getLastUsedAt() != null && !session.getLastUsedAt().isBefore(startOfDay)) return;
        try {
            sessions.touchLastUsed(session.getId(), now);
        } catch (RuntimeException e) {
            log.debug("[buyer] last_used_at refresh failed for session {}", session.getId(), e);
        }
    }

    private void populate(BuyerPrincipal principal) {
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(BuyerPrincipal.AUTHORITY)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    static boolean isBuyerPath(String uri) {
        return uri != null && BUYER_PATH.matcher(uri).matches();
    }
}
