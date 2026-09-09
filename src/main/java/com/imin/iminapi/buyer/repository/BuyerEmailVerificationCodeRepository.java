package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerEmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface BuyerEmailVerificationCodeRepository extends JpaRepository<BuyerEmailVerificationCode, UUID> {

    /**
     * Every live, unexpired code for an address, newest first — <b>all</b> of
     * them, because more than one account may hold an unverified claim on one
     * address (§2.3 rule 1) and each of those accounts has its own code.
     *
     * <p>Resolving the submission against the whole set and selecting by HMAC is
     * what binds a code to the account that asked for it. Picking the newest row
     * instead would let a later signup on the same address answer the code the
     * earlier buyer received — and then verify that address, and every order
     * joined to it, onto the later account.
     */
    List<BuyerEmailVerificationCode> findByEmailNormalizedAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String emailNormalized, Instant now);

    /**
     * Retires the account's own outstanding codes for an address before a new
     * one is issued — one live code per (address, account), never one per
     * address.
     *
     * <p>Scoped to the account deliberately. Address-scoped invalidation let a
     * stranger's signup on the same address consume the code an earlier buyer
     * was still holding, which is a lockout the 72-hour claim TTL does not cap
     * and which the earlier buyer cannot diagnose, every response on this
     * surface being neutral by design.
     */
    @Transactional
    @Modifying
    @Query("update BuyerEmailVerificationCode c set c.consumedAt = :now " +
           "where c.emailNormalized = :email and c.buyerAccountId = :accountId and c.consumedAt is null")
    int invalidateActiveForAccount(@Param("email") String emailNormalized,
                                   @Param("accountId") UUID accountId,
                                   @Param("now") Instant now);

    /**
     * Wrong-guess counter, in its own {@code REQUIRES_NEW} transaction so it
     * commits even though the caller then throws and rolls back — the same
     * trick {@code EmailVerificationCodeRepository.incrementAttempts} uses.
     * Without it the brute-force counter would be undone by the very failure it
     * is counting.
     *
     * <p><b>The {@code attempts < :max} predicate is the gate, not the caller's
     * read.</b> {@code consume} tests the counter in its own transaction and
     * increments here in another, so N concurrent wrong guesses against one
     * fresh code all read the same value and all pass — and the write past
     * {@code chk_bevc_attempts_range} (V84) then raises a
     * {@code DataIntegrityViolationException} that escapes as a 500 instead of
     * the neutral {@code INVALID_CODE}, without counting toward the hourly
     * lockout. Bounding the UPDATE itself makes the overrun a no-op: 0 rows
     * updated means "this code is already burnt", which is the same answer.
     *
     * @return 1 when the attempt was counted, 0 when the code was already at the
     *         cap — both mean the guess failed
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update BuyerEmailVerificationCode c set c.attempts = c.attempts + 1 " +
           "where c.id = :id and c.attempts < :max")
    int incrementAttempts(@Param("id") UUID id, @Param("max") int max);

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
