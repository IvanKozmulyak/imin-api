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
     * 72-hour GC of unverified rows (§4.5). Unverified rows grant nothing, and
     * expiring them is what stops an attacker from squatting an address they
     * cannot verify.
     */
    @Transactional
    @Modifying
    @Query("delete from BuyerAccountEmail e where e.verifiedAt is null and e.createdAt < :cutoff")
    int deleteUnverifiedOlderThan(@Param("cutoff") Instant cutoff);
}
