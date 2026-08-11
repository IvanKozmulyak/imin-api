package com.imin.iminapi.refund;

import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface RefundRequestTokenRepository extends JpaRepository<RefundRequestToken, UUID> {

    Optional<RefundRequestToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from RefundRequestToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
