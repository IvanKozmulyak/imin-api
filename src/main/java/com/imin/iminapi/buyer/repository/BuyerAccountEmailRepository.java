package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface BuyerAccountEmailRepository extends JpaRepository<BuyerAccountEmail, UUID> {

    List<BuyerAccountEmail> findByBuyerAccountIdOrderByCreatedAtAsc(UUID buyerAccountId);

    /**
     * The one verified row for an address, platform-wide — {@code verified_key}
     * is the marker column that carries the conditional UNIQUE, so this is a
     * single-row lookup by construction. Used by the order join and by R1.2/R1.3.
     */
    Optional<BuyerAccountEmail> findByVerifiedKey(String emailNormalized);

    Optional<BuyerAccountEmail> findByBuyerAccountIdAndEmailNormalized(UUID buyerAccountId, String emailNormalized);

    /**
     * How many verified addresses the account holds. Backs the removal rule in
     * {@code BuyerAddressService.remove}: an account must never be left with
     * zero verified addresses, because a verified address is what sign-in is
     * gated on, where transactional mail goes, and what the orders join reads.
     */
    long countByBuyerAccountIdAndVerifiedAtIsNotNull(UUID buyerAccountId);

    /** The account's primary address, or empty while it has none. */
    Optional<BuyerAccountEmail> findByPrimaryMarker(UUID buyerAccountId);

    /**
     * The newest <b>unverified</b> claim on an address, across all accounts.
     *
     * <p>Unverified rows are deliberately not unique (§2.3 rule 1): a global
     * UNIQUE spanning them would let an attacker squat an address they cannot
     * verify and lock its owner out forever. Several accounts may therefore hold
     * an unverified claim on one address at once, and "newest wins" is the rule
     * {@code resend-verification} and code issuance both use — the person who
     * most recently asked is the person waiting on the mail.
     */
    Optional<BuyerAccountEmail> findFirstByEmailNormalizedAndVerifiedAtIsNullOrderByCreatedAtDesc(
            String emailNormalized);

    /**
     * Verification re-claim (§2.3 rule 3): when an address is verified for one
     * account, every unverified claim on it elsewhere is dropped in the same
     * transaction. Whoever proves control wins; nobody is locked out by someone
     * else's typing. The now-empty squatter accounts are collected by
     * {@code BuyerAccountRepository.deleteNeverActivatedWithoutEmails}.
     */
    @Transactional
    @Modifying
    @Query("delete from BuyerAccountEmail e where e.emailNormalized = :email " +
           "and e.verifiedAt is null and e.buyerAccountId <> :accountId")
    int deleteUnverifiedClaimsElsewhere(@Param("email") String emailNormalized,
                                        @Param("accountId") UUID accountId);

    /**
     * 72-hour GC of unverified rows (§4.5). Unverified rows grant nothing, and
     * expiring them is what stops an attacker from squatting an address they
     * cannot verify.
     */
    @Transactional
    @Modifying
    @Query("delete from BuyerAccountEmail e where e.verifiedAt is null and e.createdAt < :cutoff")
    int deleteUnverifiedOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Every normalized address on the account, verified or not — the address set
     * the erasure cascade fans out over (§7.2 step 1).
     *
     * <p>Unverified rows are included deliberately. They granted nothing, but
     * they are still the buyer's address sitting in our database, and the
     * address-keyed tables the cascade clears ({@code marketing_optouts},
     * {@code buyer_verification_attempts}) have rows for addresses that were
     * only ever claimed.
     */
    @Query("select e.emailNormalized from BuyerAccountEmail e where e.buyerAccountId = :accountId")
    List<String> findEmailsByBuyerAccountId(@Param("accountId") UUID accountId);

    /**
     * The verified addresses only — the ones that resolve to a {@code Consumer}
     * and therefore to memberships (§7.2 step 2). An unverified row was never
     * proof of anything, so it never earned a place in an organizer's audience.
     */
    @Query("select e.emailNormalized from BuyerAccountEmail e " +
           "where e.buyerAccountId = :accountId and e.verifiedAt is not null")
    List<String> findVerifiedEmailsByBuyerAccountId(@Param("accountId") UUID accountId);

    /**
     * The Consumer-survival guard (§7.2). True when some buyer account holds a
     * <b>verified</b> claim on this address.
     *
     * <p>Read by {@code DsarService.executeErase} before it deletes a
     * {@code Consumer} that has no memberships left: an organizer erasing their
     * copy of a buyer must not destroy that buyer's imin account anchor. The
     * epic's original belt-and-braces {@code buyer_accounts.consumer_id} FK does
     * not exist (§4.1), so this is the only thing standing between an organizer
     * DSAR and someone else's account.
     *
     * <p>Matches on {@code verifiedKey}, not on {@code emailNormalized} plus a
     * null check — {@code verifiedKey} carries {@code uq_bae_verified_email}, so
     * this is a unique-index probe rather than a scan, and the entity keeps the
     * two columns in lockstep on every write.
     */
    boolean existsByVerifiedKey(String emailNormalized);

    /** §7.2 step 4 — the address rows themselves, deleted before the account row. */
    @Transactional
    @Modifying
    @Query("delete from BuyerAccountEmail e where e.buyerAccountId = :accountId")
    int deleteByBuyerAccountId(@Param("accountId") UUID accountId);
}
