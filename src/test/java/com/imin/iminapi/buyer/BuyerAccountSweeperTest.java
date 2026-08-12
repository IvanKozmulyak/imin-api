package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerEmailVerificationCode;
import com.imin.iminapi.buyer.model.BuyerPasswordResetToken;
import com.imin.iminapi.buyer.model.BuyerSession;
import com.imin.iminapi.buyer.model.BuyerVerificationAttempt;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerEmailVerificationCodeRepository;
import com.imin.iminapi.buyer.repository.BuyerPasswordResetTokenRepository;
import com.imin.iminapi.buyer.repository.BuyerSessionRepository;
import com.imin.iminapi.buyer.repository.BuyerVerificationAttemptRepository;
import com.imin.iminapi.buyer.service.BuyerAccountSweeper;
import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The §4.5 sweeper. Every buyer table grows a row per attempt — including
 * rate-limited ones — so this is a launch requirement, not housekeeping.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class BuyerAccountSweeperTest {

    @Autowired BuyerProperties props;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository emails;
    @Autowired BuyerSessionRepository sessions;
    @Autowired BuyerEmailVerificationCodeRepository codes;
    @Autowired BuyerPasswordResetTokenRepository resetTokens;
    @Autowired BuyerVerificationAttemptRepository attempts;

    /**
     * Constructed directly rather than autowired: the bean is wrapped by
     * ShedLock's advisor, whose {@code lockAtLeastFor = PT1M} would turn every
     * invocation after the first into a silent no-op inside a test run. Same
     * approach as {@code RefundRequestTokenSweeperTest}, but against real
     * repositories so the JPQL and the cutoffs are exercised for real.
     */
    private void sweep() {
        new BuyerAccountSweeper(sessions, codes, resetTokens, attempts, emails, accounts, props).sweep();
    }

    private BuyerAccount account(Instant createdAt, Instant activatedAt) {
        BuyerAccount a = new BuyerAccount();
        a.setActivatedAt(activatedAt);
        // Set before the insert: created_at is updatable = false (it is an
        // immutable fact), and @PrePersist only fills it when it is null.
        a.setCreatedAt(createdAt);
        return accounts.save(a);
    }

    private BuyerSession session(UUID accountId, Instant expiresAt, Instant revokedAt) {
        BuyerSession s = new BuyerSession();
        s.setBuyerAccountId(accountId);
        s.setTokenHash(UUID.randomUUID().toString().replace("-", "") + "0".repeat(32));
        s.setExpiresAt(expiresAt);
        s.setRevokedAt(revokedAt);
        return sessions.save(s);
    }

    @Test
    void expired_and_long_revoked_sessions_are_deleted_live_ones_are_not() {
        UUID id = account(Instant.now(), Instant.now()).getId();
        BuyerSession live = session(id, Instant.now().plus(30, ChronoUnit.DAYS), null);
        BuyerSession expired = session(id, Instant.now().minus(1, ChronoUnit.HOURS), null);
        BuyerSession freshlyRevoked = session(id, Instant.now().plus(30, ChronoUnit.DAYS), Instant.now());
        BuyerSession oldRevoked = session(id, Instant.now().plus(30, ChronoUnit.DAYS),
                Instant.now().minus(props.getRevokedSessionRetentionDays() + 1L, ChronoUnit.DAYS));

        sweep();

        assertThat(sessions.findById(live.getId())).isPresent();
        assertThat(sessions.findById(expired.getId())).isEmpty();
        assertThat(sessions.findById(freshlyRevoked.getId()))
                .as("recently revoked sessions are kept so 'was I logged out?' stays answerable")
                .isPresent();
        assertThat(sessions.findById(oldRevoked.getId())).isEmpty();
    }

    @Test
    void expired_codes_reset_tokens_and_old_attempts_are_deleted() {
        UUID id = account(Instant.now(), Instant.now()).getId();

        var expiredCode = new BuyerEmailVerificationCode();
        expiredCode.setBuyerAccountId(id);
        expiredCode.setEmailNormalized("sweep-" + UUID.randomUUID() + "@example.com");
        expiredCode.setCodeHash("c".repeat(64));
        expiredCode.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        expiredCode = codes.save(expiredCode);

        var liveCode = new BuyerEmailVerificationCode();
        liveCode.setBuyerAccountId(id);
        liveCode.setEmailNormalized("live-" + UUID.randomUUID() + "@example.com");
        liveCode.setCodeHash("d".repeat(64));
        liveCode.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        liveCode = codes.save(liveCode);

        var expiredReset = new BuyerPasswordResetToken();
        expiredReset.setBuyerAccountId(id);
        expiredReset.setTokenHash(UUID.randomUUID().toString().replace("-", "") + "1".repeat(32));
        expiredReset.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        expiredReset = resetTokens.save(expiredReset);

        var oldAttempt = new BuyerVerificationAttempt();
        oldAttempt.setEmailNormalized("old-" + UUID.randomUUID() + "@example.com");
        oldAttempt.setAttemptedAt(Instant.now()
                .minus(props.getVerificationAttemptRetentionHours() + 1L, ChronoUnit.HOURS));
        oldAttempt = attempts.save(oldAttempt);

        var recentAttempt = new BuyerVerificationAttempt();
        recentAttempt.setEmailNormalized("recent-" + UUID.randomUUID() + "@example.com");
        recentAttempt = attempts.save(recentAttempt);

        sweep();

        assertThat(codes.findById(expiredCode.getId())).isEmpty();
        assertThat(codes.findById(liveCode.getId())).isPresent();
        assertThat(resetTokens.findById(expiredReset.getId())).isEmpty();
        assertThat(attempts.findById(oldAttempt.getId())).isEmpty();
        assertThat(attempts.findById(recentAttempt.getId())).isPresent();
    }

    @Test
    void stale_unverified_addresses_expire_and_take_the_squatter_account_with_them() {
        long ttl = props.getUnverifiedEmailTtlHours();
        BuyerAccount squatter = account(Instant.now().minus(ttl + 1, ChronoUnit.HOURS), null);
        BuyerAccountEmail stale = BuyerAccountEmail.of(
                squatter.getId(), "stale-" + UUID.randomUUID() + "@example.com",
                BuyerAccountEmail.ADDED_VIA_MANUAL);
        stale.setCreatedAt(Instant.now().minus(ttl + 1, ChronoUnit.HOURS));
        stale = emails.save(stale);

        sweep();

        assertThat(emails.findById(stale.getId())).isEmpty();
        assertThat(accounts.findById(squatter.getId()))
                .as("an account that never had a verified address and has no rows left is the squatter's")
                .isEmpty();
    }

    @Test
    void a_real_account_survives_the_sweep_even_with_a_pending_address() {
        long ttl = props.getUnverifiedEmailTtlHours();
        BuyerAccount real = account(Instant.now().minus(ttl + 1, ChronoUnit.HOURS), Instant.now());
        BuyerAccountEmail verified = BuyerAccountEmail.of(
                real.getId(), "real-" + UUID.randomUUID() + "@example.com",
                BuyerAccountEmail.ADDED_VIA_SIGNUP);
        verified.markVerified(Instant.now());
        verified = emails.save(verified);

        BuyerAccountEmail freshPending = BuyerAccountEmail.of(
                real.getId(), "pending-" + UUID.randomUUID() + "@example.com",
                BuyerAccountEmail.ADDED_VIA_MANUAL);
        freshPending = emails.save(freshPending);

        sweep();

        assertThat(accounts.findById(real.getId())).isPresent();
        assertThat(emails.findById(verified.getId())).isPresent();
        assertThat(emails.findById(freshPending.getId()))
                .as("an address added minutes ago is not stale")
                .isPresent();
    }
}
