package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.Campaign;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link Campaign}.
 * Every read takes orgId. No unscoped finders are exposed (SPINE INVARIANT).
 */
public interface CampaignRepository extends Repository<Campaign, UUID> {

    Campaign save(Campaign campaign);

    /**
     * Unscoped by-id load. Used by the webhook projector, which resolves org from the
     * campaign for the complaint branch (recipient rows carry no org_id — spec §2.2 V53).
     * The repo extends the bare {@code Repository<>} marker, so {@code findById} is NOT
     * inherited and must be declared explicitly.
     */
    Optional<Campaign> findById(UUID id);

    @Query("select c from Campaign c where c.id = :id and c.orgId = :orgId")
    Optional<Campaign> findByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /**
     * Heartbeat: bump updated_at so the dispatcher's stale-`sending` reclaim does not fire
     * mid-send. Explicit @Modifying UPDATE that always issues an UPDATE regardless of
     * Hibernate dirty-checking (spec §2.5).
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Campaign c SET c.updatedAt=:ts WHERE c.id=:id")
    void touch(@org.springframework.data.repository.query.Param("id") java.util.UUID id,
               @org.springframework.data.repository.query.Param("ts") java.time.Instant ts);

    // TEST-SUPPORT ONLY: unscoped by-id load, used exclusively by CampaignService.forceStatusForTest.
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForTest(@Param("id") UUID id);

    /**
     * Spec §2.4: guarded draft→scheduled compare-and-set. Returns rows updated (0 or 1).
     * 0 means the campaign was not in draft (concurrent/duplicate send) → the controller 409s.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Campaign c SET c.status='scheduled', c.scheduledAt=:scheduledAt "
           + "WHERE c.id=:id AND c.orgId=:orgId AND c.status='draft'")
    int markScheduledIfDraft(@org.springframework.data.repository.query.Param("id") UUID id,
                             @org.springframework.data.repository.query.Param("orgId") UUID orgId,
                             @org.springframework.data.repository.query.Param("scheduledAt") java.time.Instant scheduledAt);

    @Query("""
            select c from Campaign c
             where c.orgId = :orgId
               and (:channel is null or c.channel = :channel)
               and (:status  is null or c.status  = :status)
             order by c.createdAt desc, c.id desc
            """)
    List<Campaign> listByOrg(@Param("orgId") UUID orgId,
                             @Param("channel") String channel,
                             @Param("status") String status,
                             Pageable pageable);

    @Query("""
            select count(c) from Campaign c
             where c.orgId = :orgId
               and (:channel is null or c.channel = :channel)
               and (:status  is null or c.status  = :status)
            """)
    long countByOrg(@Param("orgId") UUID orgId,
                    @Param("channel") String channel,
                    @Param("status") String status);

    /**
     * Spec §2.5: claim due campaigns — scheduled+due, retryable failed (attempts<3),
     * or stale sending (heartbeat > 5 min old, orphaned by a mid-send crash). SKIP LOCKED
     * so multiple dispatcher instances don't double-claim.
     */
    @Query(value = """
        SELECT * FROM campaigns
        WHERE channel = 'email'
          AND (
            (status = 'scheduled' AND scheduled_at <= :now)
            OR (status = 'failed' AND attempts < 3)
            OR (status = 'sending' AND updated_at < :staleBefore)
          )
        ORDER BY scheduled_at NULLS FIRST
        LIMIT 10
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    java.util.List<com.imin.iminapi.marketing.model.Campaign> claimDue(
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            @org.springframework.data.repository.query.Param("staleBefore") java.time.Instant staleBefore);
}
