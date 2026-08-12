package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerEmailVerificationCode;
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
public interface BuyerEmailVerificationCodeRepository extends JpaRepository<BuyerEmailVerificationCode, UUID> {

    /**
     * The live code for an address. Keyed by address rather than by account
     * because {@link #invalidateActiveForEmail} guarantees at most one is
     * outstanding platform-wide — which is what makes {@code verify-email}
     * unambiguous when several accounts hold unverified claims on one address.
     */
    Optional<BuyerEmailVerificationCode> findFirstByEmailNormalizedAndConsumedAtIsNullOrderByCreatedAtDesc(
            String emailNormalized);

    /**
     * Retires every outstanding code for an address before a new one is issued.
     * Scoped to the address, not the account: two accounts can hold unverified
     * claims on one address (§2.3 rule 1), and leaving both codes live would
     * double the guessing surface and make "which code did I just receive?"
     * genuinely ambiguous. The newest code is always the only valid one.
     */
    @Transactional
    @Modifying
    @Query("update BuyerEmailVerificationCode c set c.consumedAt = :now " +
           "where c.emailNormalized = :email and c.consumedAt is null")
    int invalidateActiveForEmail(@Param("email") String emailNormalized, @Param("now") Instant now);

    /**
     * Wrong-guess counter, in its own {@code REQUIRES_NEW} transaction so it
     * commits even though the caller then throws and rolls back — the same
     * trick {@code EmailVerificationCodeRepository.incrementAttempts} uses.
     * Without it the brute-force counter would be undone by the very failure it
     * is counting.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update BuyerEmailVerificationCode c set c.attempts = c.attempts + 1 where c.id = :id")
    int incrementAttempts(@Param("id") UUID id);

    @Transactional
    @Modifying
    @Query("delete from BuyerEmailVerificationCode c where c.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    /** §7.2 step 4 — outstanding codes carry the buyer's address; erasure drops them. */
    @Transactional
    @Modifying
    @Query("delete from BuyerEmailVerificationCode c where c.buyerAccountId = :accountId")
    int deleteByBuyerAccountId(@Param("accountId") UUID accountId);
}
