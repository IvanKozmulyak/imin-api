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
}
