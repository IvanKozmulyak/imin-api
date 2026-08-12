package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code @RepositoryRestResource(exported = false)} is mandatory here as
 * everywhere — {@code RepositoryExportGuardTest} fails the build without it, and
 * this table maps provider subjects to buyer accounts.
 */
@RepositoryRestResource(exported = false)
public interface BuyerIdentityRepository extends JpaRepository<BuyerIdentity, UUID> {

    /** The identity match that resolves a returning social sign-in — subject, not email. */
    Optional<BuyerIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);

    List<BuyerIdentity> findByBuyerAccountId(UUID buyerAccountId);
}
