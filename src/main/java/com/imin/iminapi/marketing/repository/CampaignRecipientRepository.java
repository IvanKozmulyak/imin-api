package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.CampaignRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, UUID> {

    List<CampaignRecipient> findByCampaignIdAndStatus(UUID campaignId, String status);

    java.util.Optional<CampaignRecipient> findByProviderMessageId(String providerMessageId);

    java.util.List<CampaignRecipient> findByCampaignId(UUID campaignId, org.springframework.data.domain.Pageable pageable);

    java.util.List<CampaignRecipient> findByCampaignIdAndStatus(UUID campaignId, String status, org.springframework.data.domain.Pageable pageable);

    long countByCampaignIdAndStatus(UUID campaignId, String status);

    long countByCampaignId(UUID campaignId);

    /**
     * Claim up to :limit pending, retryable (attempt_count < 3) rows for one campaign,
     * skipping rows another sender thread already holds (spec §2.5). Native so we can
     * use FOR UPDATE SKIP LOCKED, which Spring Data does not express portably.
     */
    @Query(value = """
        SELECT * FROM campaign_recipients
        WHERE campaign_id = :campaignId AND status = 'pending' AND attempt_count < 3
        ORDER BY id
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<CampaignRecipient> claimPendingBatch(@Param("campaignId") UUID campaignId,
                                              @Param("limit") int limit);

    /**
     * Count recent sends for a membership across all campaigns — backs the per-member
     * frequency floor in {@link com.imin.iminapi.marketing.service.CampaignVolumeGuard}
     * (spec §7). A member contacted within the floor window is skipped.
     */
    @Query("""
            select count(r) from CampaignRecipient r
             where r.membershipId = :membershipId
               and r.status in ('sent','delivered','opened','clicked')
               and r.lastEventAt >= :since
            """)
    long countRecentSendsForMembership(@Param("membershipId") UUID membershipId,
                                       @Param("since") java.time.Instant since);

    /**
     * DSAR (spec §7): null the recipient PII (email/phone/rendered body) for every row belonging
     * to an erased membership, keeping status/skip_reason as an anonymous audit aggregate. Called
     * from {@code DsarService.executeErase} BEFORE the membership hard-delete; V53's
     * {@code ON DELETE SET NULL} FK then nulls {@code membership_id} when the delete proceeds.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
        "UPDATE CampaignRecipient r SET r.email=null, r.phoneE164=null, r.renderedBody=null "
        + "WHERE r.membershipId=:membershipId")
    void redactPiiByMembershipId(@org.springframework.data.repository.query.Param("membershipId") UUID membershipId);
}
