package com.imin.iminapi.refund;

import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface RefundRequestTokenRepository extends JpaRepository<RefundRequestToken, UUID> {

    Optional<RefundRequestToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from RefundRequestToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    /** Consumes the token in its own transaction, so the burn survives the caller's 4xx rolling back. */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update RefundRequestToken t set t.consumedAt = :at where t.id = :id and t.consumedAt is null")
    int markConsumed(@Param("id") UUID id, @Param("at") Instant at);
}
