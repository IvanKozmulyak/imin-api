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
}
