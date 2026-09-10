package com.imin.iminapi.repository;

import com.imin.iminapi.model.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {

    Optional<EmailVerificationCode> findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Query("UPDATE EmailVerificationCode c SET c.consumedAt = :now " +
           "WHERE c.userId = :userId AND c.consumedAt IS NULL")
    int invalidateActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Atomic increment of the brute-force counter, committed in a NEW transaction.
     * The outer caller's transaction is expected to roll back when the wrong-code
     * INVALID_CODE is thrown — REQUIRES_NEW ensures the increment commits anyway,
     * preserving the brute-force protection.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE EmailVerificationCode c SET c.attempts = c.attempts + 1 WHERE c.id = :id")
    int incrementAttempts(@Param("id") UUID id);

    /**
     * Wrong guesses made against <b>every</b> code this user has been issued
     * inside a window — the input to the per-address lockout.
     *
     * <p>The per-code {@code attempts} cap alone is not a brute-force control:
     * {@code /auth/resend-verification} mints a fresh row, so five guesses per
     * code times three resends per fifteen minutes is roughly 1,440 guesses a
     * day against a code space, with a live session as the prize. Summing across
     * codes is what makes the budget survive a resend.
     *
     * <p>Counted from the existing {@code attempts} column rather than a new
     * attempts table (the shape the buyer side uses) because the counter already
     * exists here, is already written outside the caller's transaction by
     * {@link #incrementAttempts}, and is therefore already test-visible — which
     * is the property {@code RateLimitConfig} being {@code @Profile("!test")}
     * denies a bucket.
     */
    @Query("SELECT COALESCE(SUM(c.attempts), 0) FROM EmailVerificationCode c "
           + "WHERE c.userId = :userId AND c.createdAt >= :since")
    long sumAttemptsSince(@Param("userId") UUID userId, @Param("since") Instant since);
}
