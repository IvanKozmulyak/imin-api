package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.CampaignRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, UUID> {

    List<CampaignRecipient> findByCampaignIdAndStatus(UUID campaignId, String status);

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
}
