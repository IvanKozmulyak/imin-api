package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.model.BuyerVerificationAttempt;
import com.imin.iminapi.buyer.repository.BuyerVerificationAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Writes {@code buyer_verification_attempts} rows in their <b>own</b>
 * transaction.
 *
 * <p>This is a separate bean purely so the {@code REQUIRES_NEW} proxy applies:
 * a self-call inside {@link BuyerEmailVerificationService} would bypass it, and
 * the row would then be rolled back by the very exception it exists to count.
 * That is the same reasoning behind
 * {@code EmailVerificationCodeRepository.incrementAttempts}, and getting it
 * wrong would silently disable the §2.2 lockout while leaving every test that
 * asserts a single failure green.
 */
@Service
public class BuyerVerificationAttemptRecorder {

    /**
     * Stands in for a caller whose IP the container did not give us. A row is
     * still worth writing — it just cannot be attributed — and a literal beats
     * a NULL both because the lookup is an equality and because a nullable
     * String parameter is the Postgres {@code lower(bytea)} trap waiting to
     * happen.
     */
    static final String UNKNOWN_IP = "unknown";

    private final BuyerVerificationAttemptRepository attempts;

    public BuyerVerificationAttemptRecorder(BuyerVerificationAttemptRepository attempts) {
        this.attempts = attempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String emailNormalized, String clientIp, boolean succeeded) {
        BuyerVerificationAttempt row = new BuyerVerificationAttempt();
        row.setEmailNormalized(emailNormalized);
        row.setClientIp(normalizeIp(clientIp));
        row.setSucceeded(succeeded);
        attempts.save(row);
    }

    /**
     * Plain {@code readOnly}, joining whatever transaction the caller has.
     *
     * <p>The {@code REQUIRES_NEW} above is {@link #record}'s requirement, not
     * this bean's: a row has to survive the caller's rollback, a COUNT has
     * nothing to survive. Propagating a new transaction here only suspended the
     * caller's and took a second connection out of the pool for the duration of
     * one query — on the first statement of every
     * {@code POST /buyer/auth/verify-email}. Nothing depends on the isolation:
     * the count is read before any write in that request.
     */
    @Transactional(readOnly = true)
    public long countFailuresSince(String emailNormalized, String clientIp, Instant since) {
        return attempts.countByEmailNormalizedAndClientIpAndSucceededFalseAndAttemptedAtAfter(
                emailNormalized, normalizeIp(clientIp), since);
    }

    static String normalizeIp(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? UNKNOWN_IP : clientIp.trim();
    }
}
