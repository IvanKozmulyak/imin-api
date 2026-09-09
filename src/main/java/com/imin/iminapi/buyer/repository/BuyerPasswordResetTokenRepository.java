package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerPasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface BuyerPasswordResetTokenRepository extends JpaRepository<BuyerPasswordResetToken, UUID> {

    /** Lookup by SHA-256 hex; the raw token exists only in the buyer's inbox. */
    Optional<BuyerPasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Retires every outstanding reset token for an account.
     *
     * <p>The mirror of {@code BuyerEmailVerificationService.issue}: only the
     * newest link may work. A reset happens because somebody else may hold the
     * credential, so an older link still live inside the 30-minute TTL —
     * forwarded, leaked from a shared inbox, sitting in a proxy log — could be
     * redeemed right after the owner's own recovery and take the account back,
     * revoking their fresh sessions on the way through.
     */
    @Transactional
    @Modifying
    @Query("update BuyerPasswordResetToken t set t.consumedAt = :now " +
           "where t.buyerAccountId = :accountId and t.consumedAt is null")
    int consumeAllForAccount(@Param("accountId") UUID accountId, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("delete from BuyerPasswordResetToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    /** §7.2 step 4 — a live reset token on an erased account would be a way back in. */
    @Transactional
    @Modifying
    @Query("delete from BuyerPasswordResetToken t where t.buyerAccountId = :accountId")
    int deleteByBuyerAccountId(@Param("accountId") UUID accountId);
}
