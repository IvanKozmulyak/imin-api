package com.imin.iminapi.service.gate;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.gate.GateLoginRequest;
import com.imin.iminapi.dto.gate.GateLoginResponse;
import com.imin.iminapi.model.GateCredential;
import com.imin.iminapi.model.GateSession;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.GateCredentialRepository;
import com.imin.iminapi.repository.GateSessionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Full-context tests for gate auth: login, rotate, status, and the bearer-token
 * round trip via {@link GateAuthService#authenticate(String)}. Uses real H2
 * persistence so the credential upsert path is exercised against the actual
 * DDL (column constraints, FK cascades) rather than mocks.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class GateAuthServiceTest {

    @Autowired GateAuthService service;
    @Autowired GateCredentialRepository credentials;
    @Autowired GateSessionRepository sessions;
    @Autowired OrganizationRepository orgs;
    @Autowired TokenService tokens;

    private Organization org;
    private AuthPrincipal owner;

    @BeforeEach
    void seed() {
        sessions.deleteAll();
        credentials.deleteAll();
        orgs.deleteAll();
        Organization o = new Organization();
        o.setName("Gate Test Org");
        o.setSlug("gate-test-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("gate@example.test");
        o.setCountry("DE");
        org = orgs.save(o);
        owner = new AuthPrincipal(UUID.randomUUID(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void clean() {
        sessions.deleteAll();
        credentials.deleteAll();
        orgs.deleteAll();
    }

    // ── rotate / status ──────────────────────────────────────────────────────

    @Test
    void status_when_no_credential_yet_returns_hasCredential_false() {
        var status = service.status(owner, org.getId());
        assertThat(status.hasCredential()).isFalse();
        assertThat(status.lastRotatedAt()).isNull();
        assertThat(status.username()).isEqualTo(org.getSlug());
    }

    @Test
    void rotate_then_status_returns_hasCredential_true() {
        service.rotate(owner, org.getId(), "long-enough-password");
        var status = service.status(owner, org.getId());
        assertThat(status.hasCredential()).isTrue();
        assertThat(status.lastRotatedAt()).isNotNull();
        assertThat(status.username()).isEqualTo(org.getSlug());
    }

    @Test
    void rotate_upserts_overwrites_previous_hash() {
        service.rotate(owner, org.getId(), "first-password-123");
        String hash1 = credentials.findByOrgId(org.getId()).orElseThrow().getPasswordHash();

        service.rotate(owner, org.getId(), "second-password-456");
        String hash2 = credentials.findByOrgId(org.getId()).orElseThrow().getPasswordHash();

        assertThat(hash2).isNotEqualTo(hash1);
        // exactly one row per org (PK is org_id)
        assertThat(credentials.count()).isEqualTo(1);
    }

    @Test
    void rotate_invalidates_existing_gate_sessions_for_org() {
        // Org #1: a credential plus two live device sessions.
        service.rotate(owner, org.getId(), "first-password-123");
        String hashBefore = credentials.findByOrgId(org.getId()).orElseThrow().getPasswordHash();
        String tokenA = service.login(new GateLoginRequest(org.getSlug(), "first-password-123")).token();
        String tokenB = service.login(new GateLoginRequest(org.getSlug(), "first-password-123")).token();
        assertThat(service.authenticate(tokenA)).isPresent();
        assertThat(service.authenticate(tokenB)).isPresent();

        // Org #2: a completely independent org with its own active session —
        // must not be touched by rotating org #1's credential.
        Organization other = new Organization();
        other.setName("Untouched Org");
        other.setSlug("untouched-" + UUID.randomUUID().toString().substring(0, 8));
        other.setContactEmail("untouched@example.test");
        other.setCountry("DE");
        Organization other2 = orgs.save(other);
        AuthPrincipal otherOwner = new AuthPrincipal(
                UUID.randomUUID(), other2.getId(), UserRole.OWNER, UUID.randomUUID());
        service.rotate(otherOwner, other2.getId(), "other-password-789");
        String tokenC = service.login(new GateLoginRequest(other2.getSlug(), "other-password-789")).token();
        assertThat(service.authenticate(tokenC)).isPresent();

        // Act: rotate org #1's password.
        service.rotate(owner, org.getId(), "new-password-xyz");

        // Org #1's existing sessions are gone.
        assertThat(service.authenticate(tokenA)).isEmpty();
        assertThat(service.authenticate(tokenB)).isEmpty();
        assertThat(sessions.findAll().stream()
                .filter(s -> s.getOrgId().equals(org.getId()))
                .count())
                .isZero();

        // Org #2 is untouched — cross-org isolation.
        assertThat(service.authenticate(tokenC)).isPresent();
        assertThat(sessions.findAll().stream()
                .filter(s -> s.getOrgId().equals(other2.getId()))
                .count())
                .isEqualTo(1);

        // And the new password hash is in place on org #1's credential.
        String hashAfter = credentials.findByOrgId(org.getId()).orElseThrow().getPasswordHash();
        assertThat(hashAfter).isNotEqualTo(hashBefore);
    }

    @Test
    void rotate_for_other_org_is_forbidden() {
        Organization other = new Organization();
        other.setName("Other Org");
        other.setSlug("other-" + UUID.randomUUID().toString().substring(0, 8));
        other.setContactEmail("other@example.test");
        other.setCountry("DE");
        Organization saved = orgs.save(other);

        assertThatThrownBy(() -> service.rotate(owner, saved.getId(), "long-enough-password"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ── login ────────────────────────────────────────────────────────────────

    @Test
    void login_with_correct_slug_and_password_returns_token() {
        service.rotate(owner, org.getId(), "correct-password-123");

        GateLoginResponse resp = service.login(new GateLoginRequest(org.getSlug(), "correct-password-123"));

        assertThat(resp.token()).isNotBlank();
        assertThat(resp.orgId()).isEqualTo(org.getId());
        assertThat(resp.orgName()).isEqualTo(org.getName());
        assertThat(resp.expiresAt()).isAfter(Instant.now());

        // session persisted as hash, not plaintext
        Optional<GateSession> s = sessions.findByTokenHashAndRevokedAtIsNull(tokens.hashOf(resp.token()));
        assertThat(s).isPresent();
        assertThat(s.get().getOrgId()).isEqualTo(org.getId());
    }

    @Test
    void login_with_wrong_password_returns_401_invalid_credentials() {
        service.rotate(owner, org.getId(), "correct-password-123");
        assertThatThrownBy(() -> service.login(new GateLoginRequest(org.getSlug(), "wrong-password-456")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void login_with_unknown_slug_returns_same_error_as_wrong_password() {
        // No credential rotation here at all.
        ApiException ex = (ApiException) catchThrowable(() ->
                service.login(new GateLoginRequest("nonexistent-slug-zzzz", "any-password")));
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
        // message must be generic — never disclose whether slug or password was wrong
        assertThat(ex.getMessage().toLowerCase())
                .doesNotContain("slug").doesNotContain("organization").doesNotContain("found");
    }

    @Test
    void login_when_org_exists_but_no_credential_yet_returns_invalid_credentials() {
        // Org exists, but rotate() was never called.
        ApiException ex = (ApiException) catchThrowable(() ->
                service.login(new GateLoginRequest(org.getSlug(), "any-password")));
        assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    // ── authenticate (bearer-token round trip) ───────────────────────────────

    @Test
    void authenticate_returns_gate_principal_for_valid_token() {
        service.rotate(owner, org.getId(), "correct-password-123");
        String token = service.login(new GateLoginRequest(org.getSlug(), "correct-password-123")).token();

        Optional<AuthPrincipal> p = service.authenticate(token);

        assertThat(p).isPresent();
        assertThat(p.get().isGate()).isTrue();
        assertThat(p.get().orgId()).isEqualTo(org.getId());
        assertThat(p.get().userId()).isNull();
        assertThat(p.get().actorLabel()).isEqualTo("gate:" + org.getId());
    }

    @Test
    void authenticate_returns_empty_for_unknown_token() {
        assertThat(service.authenticate("not-a-real-token")).isEmpty();
    }

    @Test
    void authenticate_returns_empty_for_expired_session() {
        service.rotate(owner, org.getId(), "correct-password-123");
        String token = service.login(new GateLoginRequest(org.getSlug(), "correct-password-123")).token();
        // Manually expire it.
        GateSession s = sessions.findByTokenHashAndRevokedAtIsNull(tokens.hashOf(token)).orElseThrow();
        s.setExpiresAt(Instant.now().minusSeconds(60));
        sessions.save(s);

        assertThat(service.authenticate(token)).isEmpty();
    }
}
