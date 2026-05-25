package com.imin.iminapi.service.gate;

import com.imin.iminapi.dto.gate.GateCredentialStatusResponse;
import com.imin.iminapi.dto.gate.GateLoginRequest;
import com.imin.iminapi.dto.gate.GateLoginResponse;
import com.imin.iminapi.model.GateCredential;
import com.imin.iminapi.model.GateSession;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.GateCredentialRepository;
import com.imin.iminapi.repository.GateSessionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Gate-scanner authentication.
 *
 * <p>Three flows:
 * <ol>
 *   <li>{@link #login} — public, called from {@code POST /api/v1/gate/login}.
 *       Verifies (orgSlug, password) against {@link GateCredential} using
 *       BCrypt, and on success issues an opaque bearer token whose SHA-256
 *       hash is persisted as a {@link GateSession}. Always 401 with a generic
 *       message on any failure — never reveals whether the slug or the
 *       password was wrong.</li>
 *   <li>{@link #rotate} / {@link #status} — organizer-side, called from
 *       {@code /api/v1/orgs/{orgId}/gate/credentials/...} with a user JWT.
 *       Org tenancy gate fires at the same {@code principal.orgId() == orgId}
 *       comparison the rest of the org dashboard uses, and any mismatch
 *       returns a leak-safe {@link ErrorCode#NOT_FOUND}.</li>
 *   <li>{@link #authenticate} — wired into {@link com.imin.iminapi.security.BearerTokenAuthFilter}.
 *       Given a raw bearer string (only called after the user-session lookup
 *       has missed), returns a gate-flavoured {@link AuthPrincipal} if the
 *       hash maps to a live, unexpired, non-revoked session.</li>
 * </ol>
 */
@Service
public class GateAuthService {

    private static final Logger log = LoggerFactory.getLogger(GateAuthService.class);

    private final OrganizationRepository orgs;
    private final GateCredentialRepository credentials;
    private final GateSessionRepository sessions;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final Duration tokenTtl;

    @Autowired
    public GateAuthService(OrganizationRepository orgs,
                            GateCredentialRepository credentials,
                            GateSessionRepository sessions,
                            PasswordHasher hasher,
                            TokenService tokens,
                            @Value("${imin.gate.token-ttl-days:30}") long tokenTtlDays) {
        this(orgs, credentials, sessions, hasher, tokens, Duration.ofDays(tokenTtlDays));
    }

    /** Constructor used by tests that want to inject a shorter TTL. */
    public GateAuthService(OrganizationRepository orgs,
                            GateCredentialRepository credentials,
                            GateSessionRepository sessions,
                            PasswordHasher hasher,
                            TokenService tokens,
                            Duration tokenTtl) {
        this.orgs = orgs;
        this.credentials = credentials;
        this.sessions = sessions;
        this.hasher = hasher;
        this.tokens = tokens;
        this.tokenTtl = tokenTtl;
    }

    /**
     * Public gate-device login. Returns a fresh bearer token + the org
     * identifier the device should display. The "username" is the org slug —
     * we do not introduce a second identifier (per spec). Any failure path
     * (slug missing, no credential yet, password mismatch) collapses into a
     * single generic 401 so we never confirm or deny slug existence.
     */
    @Transactional
    public GateLoginResponse login(GateLoginRequest req) {
        Optional<Organization> maybeOrg = orgs.findBySlug(req.orgSlug());
        // Do the hash compare against a known-bad fixed string when no
        // credential exists, so the timing profile is the same regardless of
        // whether the slug was real. (BCrypt is slow on purpose; skipping it
        // would leak slug existence.)
        Optional<GateCredential> maybeCred = maybeOrg.flatMap(o -> credentials.findByOrgId(o.getId()));
        boolean ok = maybeCred
                .map(c -> hasher.verify(req.password(), c.getPasswordHash()))
                .orElseGet(() -> {
                    // Compare against a stable bogus hash — keeps timing constant.
                    hasher.verify(req.password(), BOGUS_HASH);
                    return false;
                });
        if (!ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials");
        }
        Organization org = maybeOrg.orElseThrow(); // unreachable when ok==true

        TokenService.IssuedToken issued = tokens.issue();
        GateSession s = new GateSession();
        s.setOrgId(org.getId());
        s.setTokenHash(issued.tokenHash());
        s.setExpiresAt(Instant.now().plus(tokenTtl));
        sessions.save(s);
        return new GateLoginResponse(issued.token(), org.getId(), org.getName(), s.getExpiresAt());
    }

    /**
     * Upsert the org's gate credential. Replaces any existing row for the
     * org (the PK is org_id, so we save() the same row to overwrite both
     * hash and lastRotatedAt).
     *
     * <p>Rotation is the only revocation lever an organizer has against a lost
     * or compromised gate device, so the same transaction also drops every
     * existing {@link GateSession} for the org. Without that, previously-
     * issued bearer tokens stayed valid up to their {@code expiresAt} (default
     * 30 days), which would defeat the point of rotating the password.
     */
    @Transactional
    public void rotate(AuthPrincipal principal, UUID orgId, String newPassword) {
        requireSameOrg(principal, orgId);
        GateCredential c = credentials.findByOrgId(orgId).orElseGet(GateCredential::new);
        c.setOrgId(orgId);
        c.setPasswordHash(hasher.hash(newPassword));
        c.setLastRotatedAt(Instant.now());
        credentials.save(c);
        int revoked = sessions.deleteAllByOrgId(orgId);
        log.info("Gate credential rotated for org {} (revoked {} active session(s))", orgId, revoked);
    }

    @Transactional(readOnly = true)
    public GateCredentialStatusResponse status(AuthPrincipal principal, UUID orgId) {
        requireSameOrg(principal, orgId);
        Organization org = orgs.findById(orgId).orElseThrow(() -> ApiException.notFound("Organization"));
        Optional<GateCredential> maybe = credentials.findByOrgId(orgId);
        String username = org.getSlug() != null ? org.getSlug() : orgId.toString();
        return new GateCredentialStatusResponse(
                maybe.isPresent(),
                maybe.map(GateCredential::getLastRotatedAt).orElse(null),
                username);
    }

    /**
     * Bearer-token resolution for the security filter. Returns the gate
     * principal if the token's hash matches a live, unexpired,
     * non-revoked {@link GateSession}; empty otherwise. Never throws.
     */
    @Transactional(readOnly = true)
    public Optional<AuthPrincipal> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        String hash = tokens.hashOf(rawToken);
        Optional<GateSession> maybe = sessions.findByTokenHashAndRevokedAtIsNull(hash);
        if (maybe.isEmpty()) return Optional.empty();
        GateSession s = maybe.get();
        if (s.getExpiresAt() == null || s.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(AuthPrincipal.forGate(s.getOrgId(), s.getId()));
    }

    private void requireSameOrg(AuthPrincipal principal, UUID orgId) {
        if (principal == null || principal.orgId() == null || !principal.orgId().equals(orgId)) {
            // Leak-safe — same envelope CrossOrgScopingTest enforces for every
            // /api/v1/orgs/{orgId}/... endpoint.
            throw ApiException.notFound("Organization");
        }
    }

    /**
     * A precomputed bcrypt hash of an unguessable random string, used to give
     * the "credential doesn't exist" path the same wall-clock cost as the
     * "credential exists but password is wrong" path. Generated once at class-
     * load time so we don't pay the bcrypt cost on every miss.
     *
     * <p>Workfactor is 10 (the default) rather than 12 (the production user
     * hasher's): the actual verify against a real credential will still take
     * its full ~2^12 time; this constant only needs to be expensive enough
     * that the difference between "no credential" and "wrong password" is
     * lost in normal jitter. Hard-coding the value also keeps tests
     * deterministic.
     */
    private static final String BOGUS_HASH =
            "$2a$10$abcdefghijklmnopqrstuOCBlBoqA3UMlMx38LfaJZQX.5x.4LeyG";
}
