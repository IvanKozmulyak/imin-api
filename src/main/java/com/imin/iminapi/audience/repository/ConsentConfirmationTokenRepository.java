package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.ConsentConfirmationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface ConsentConfirmationTokenRepository extends JpaRepository<ConsentConfirmationToken, UUID> {

    /** The newest confirmation email of this member, for the 24 h resend window. */
    Optional<ConsentConfirmationToken> findFirstByMembershipIdOrderBySentAtDesc(UUID membershipId);

    List<ConsentConfirmationToken> findByMembershipIdOrderBySentAtAsc(UUID membershipId);

    /** Burns the token; 1 only for the caller that got there first, so a race cannot confirm twice. */
    @Modifying(flushAutomatically = true)
    @Query("update ConsentConfirmationToken t set t.usedAt = :at where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") UUID id, @Param("at") Instant at);
}
