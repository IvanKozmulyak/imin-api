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

    /** True while this member has an unclicked, unexpired email for a still-pending {@code source} sign-up. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM consent_confirmation_tokens t"
            + " JOIN consent_records r ON r.id = t.consent_record_id"
            + " WHERE t.membership_id = :membershipId AND t.used_at IS NULL AND t.expires_at > :now"
            + " AND r.source = :source AND r.confirmed_at IS NULL)", nativeQuery = true)
    boolean existsLiveForPendingSource(@Param("membershipId") UUID membershipId, @Param("source") String source,
                                       @Param("now") Instant now);

    /** Unclicked emails this member already got for sign-ups of the same source as {@code consentRecordId}. */
    @Query(value = "SELECT COUNT(*) FROM consent_confirmation_tokens t"
            + " JOIN consent_records r ON r.id = t.consent_record_id"
            + " WHERE t.membership_id = :membershipId AND t.used_at IS NULL"
            + " AND r.source = (SELECT n.source FROM consent_records n WHERE n.id = :consentRecordId)",
            nativeQuery = true)
    long countUnclickedOfSameSource(@Param("membershipId") UUID membershipId,
                                    @Param("consentRecordId") UUID consentRecordId);

    /** Drops the survey scores held on this member's unclicked emails, beside every clear of {@code memberships.nps}. */
    @Modifying(flushAutomatically = true)
    @Query("update ConsentConfirmationToken t set t.npsScore = null where t.membershipId = :id and t.usedAt is null")
    int clearHeldScores(@Param("id") UUID membershipId);

    /** Burns the token and drops its held score; 1 only for the caller that got there first, so a race cannot confirm twice. */
    @Modifying(flushAutomatically = true)
    @Query("update ConsentConfirmationToken t set t.usedAt = :at, t.npsScore = null where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") UUID id, @Param("at") Instant at);
}
