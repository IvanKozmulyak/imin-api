package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.BuyerProperties;
import com.imin.iminapi.buyer.model.BuyerSession;
import com.imin.iminapi.buyer.repository.BuyerSessionRepository;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.security.TokenService;
import com.imin.iminapi.util.Times;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Mints, revokes and describes buyer sessions. This is the seam every
 * credential flow in R1.2 sits on: signup-verify, login, password reset and the
 * Google callback all end in {@link #issue(UUID, String)} and attach the
 * returned cookie.
 *
 * <p>Sessions are opaque and server-side. The raw token is 32 {@code SecureRandom}
 * bytes from the shared {@link TokenService} and only its SHA-256 hex ever
 * reaches the database, so a database read cannot impersonate anybody. There is
 * no rotation, because there is nothing to rotate on an opaque server-side
 * session — {@code revoked_at} is the whole revocation story (epic §2.5).
 *
 * <p>Revocation is mandatory on five events (§2.2): password change (all but
 * the acting session), password-reset consumption, primary-address change,
 * unlinking Google, and scheduling deletion. Those call sites land in R1.2 /
 * R1.3 / R1.5; {@link #revokeAll(UUID)} is what they call.
 */
@Service
public class BuyerSessionService {

    private final BuyerSessionRepository sessions;
    private final TokenService tokens;
    private final BuyerProperties props;

    public BuyerSessionService(BuyerSessionRepository sessions,
                               TokenService tokens,
                               BuyerProperties props) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.props = props;
    }

    /** A freshly minted session plus the {@code Set-Cookie} that carries it. */
    public record IssuedSession(UUID sessionId, String rawToken, ResponseCookie cookie) {}

    @Transactional
    public IssuedSession issue(UUID accountId, String userAgent) {
        TokenService.IssuedToken token = tokens.issue();
        Duration ttl = Duration.ofDays(props.getSessionTtlDays());

        BuyerSession session = new BuyerSession();
        session.setBuyerAccountId(accountId);
        session.setTokenHash(token.tokenHash());
        session.setIssuedAt(Times.nowMicros());
        session.setLastUsedAt(Times.nowMicros());
        session.setExpiresAt(Times.nowMicros().plus(ttl));
        session.setUserAgent(truncate(userAgent));
        BuyerSession saved = sessions.save(session);

        return new IssuedSession(saved.getId(), token.token(),
                BuyerSessionCookie.issue(token.token(), ttl));
    }

    /**
     * Revokes the session behind a raw cookie value. Silent when the cookie is
     * unknown, already revoked or absent — logout is idempotent and always
     * answers 204 so the frontend can clean up after an expired session.
     */
    @Transactional
    public void revokeByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        sessions.revokeByTokenHash(tokens.hashOf(rawToken), Instant.now());
    }

    /** Revokes every live session for the account. Returns how many were live. */
    @Transactional
    public int revokeAll(UUID accountId) {
        return sessions.revokeAllForAccount(accountId, Instant.now());
    }

    /**
     * Revokes every live session for the account except {@code keepSessionId}.
     * Returns how many were live. Used by the password change, which
     * deliberately spares the acting session.
     */
    @Transactional
    public int revokeAllExcept(UUID accountId, UUID keepSessionId) {
        return sessions.revokeAllForAccountExcept(accountId, keepSessionId, Instant.now());
    }

    /** The {@code Set-Cookie} that removes the session cookie from the browser. */
    public ResponseCookie clearCookie() {
        return BuyerSessionCookie.clear();
    }

    private static String truncate(String userAgent) {
        if (userAgent == null) return null;
        return userAgent.length() <= 512 ? userAgent : userAgent.substring(0, 512);
    }
}
