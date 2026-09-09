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

    private final BuyerVerificationAttemptRepository attempts;

    public BuyerVerificationAttemptRecorder(BuyerVerificationAttemptRepository attempts) {
        this.attempts = attempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String emailNormalized, boolean succeeded) {
        BuyerVerificationAttempt row = new BuyerVerificationAttempt();
        row.setEmailNormalized(emailNormalized);
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
    public long countFailuresSince(String emailNormalized, Instant since) {
        return attempts.countByEmailNormalizedAndSucceededFalseAndAttemptedAtAfter(emailNormalized, since);
    }
}
