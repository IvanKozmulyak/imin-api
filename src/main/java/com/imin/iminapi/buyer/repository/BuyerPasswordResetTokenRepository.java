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

    @Transactional
    @Modifying
    @Query("delete from BuyerPasswordResetToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
