package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.Campaign;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link Campaign}.
 * Every read takes orgId. No unscoped finders are exposed (SPINE INVARIANT).
 */
// Internal store — never exposed over Spring Data REST (auto-export both leaks
// the table into the public OpenAPI and crashes springdoc on ambiguous
// overloaded search mappings).
@RepositoryRestResource(exported = false)
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

    /** Row-locked load for an edit: a concurrent send or claim waits for it, and the claim's SKIP LOCKED passes it over. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id and c.orgId = :orgId")
    Optional<Campaign> findByIdAndOrgIdForUpdate(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /**
     * Hard-delete a campaign row. The repo extends the bare {@code Repository<>} marker, so
     * {@code delete} is NOT inherited and must be declared explicitly. Recipient rows are
     * removed first (see {@link CampaignRecipientRepository#deleteByCampaignId}) — drafts
     * should have none, but this keeps the delete order FK-safe regardless of the DB-level
     * ON DELETE CASCADE.
     */
    void delete(Campaign campaign);

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

    /** The claim's flip, run inside the claim transaction while its row locks are held: status and heartbeat only. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Campaign c SET c.status='sending', c.updatedAt=:claimedAt WHERE c.id IN :ids")
    int markClaimed(@Param("ids") java.util.Collection<UUID> ids, @Param("claimedAt") java.time.Instant claimedAt);

    /**
     * Gives back a claimed campaign the run never started, with the status and heartbeat it held before the claim.
     * Guarded on the claim's own stamp, so a writer that changed the row since keeps its change.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Campaign c SET c.status=:priorStatus, c.updatedAt=:priorUpdatedAt "
           + "WHERE c.id=:id AND c.status='sending' AND c.updatedAt=:claimedAt")
    int releaseClaim(@Param("id") UUID id, @Param("priorStatus") String priorStatus,
                     @Param("priorUpdatedAt") java.time.Instant priorUpdatedAt,
                     @Param("claimedAt") java.time.Instant claimedAt);

    /** Guarded scheduled→canceled; 0 rows means the campaign left 'scheduled' (claimed, sent, canceled). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Campaign c SET c.status='canceled', c.updatedAt=:now "
           + "WHERE c.id=:id AND c.orgId=:orgId AND c.status='scheduled'")
    int cancelIfScheduled(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("now") java.time.Instant now);

    /** Guarded failed→scheduled while attempts remain; 0 rows means the dispatcher (or another retry) got there first. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Campaign c SET c.status='scheduled', c.scheduledAt=:now, c.updatedAt=:now "
           + "WHERE c.id=:id AND c.orgId=:orgId AND c.status='failed' AND c.attempts < 3")
    int retryIfFailed(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("now") java.time.Instant now);

    /** The stored status, re-read by the drive before each batch. */
    @Query("select c.status from Campaign c where c.id = :id")
    Optional<String> findStatusById(@Param("id") UUID id);

    /** The materialization snapshot counts, written without touching status. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Campaign c SET c.recipientCount=:recipientCount, c.excludedCount=:excludedCount, "
           + "c.exclusionSummary=:exclusionSummary WHERE c.id=:id")
    int recordMaterialized(@Param("id") UUID id, @Param("recipientCount") Integer recipientCount,
                           @Param("excludedCount") Integer excludedCount,
                           @Param("exclusionSummary") String exclusionSummary);

    /** A drained campaign becomes 'sent' only while it is still 'sending', never over a cancel. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Campaign c SET c.status='sent', c.sentAt=:sentAt, c.updatedAt=:sentAt "
           + "WHERE c.id=:id AND c.status='sending'")
    int markSentIfSending(@Param("id") UUID id, @Param("sentAt") java.time.Instant sentAt);

    /** Fails a campaign that is still on its way out; a canceled or sent one keeps its status. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE campaigns SET status = 'failed', attempts = attempts + 1, last_error = :error, "
           + "updated_at = :now WHERE id = :id AND status IN ('scheduled', 'sending')", nativeQuery = true)
    int markFailedIfActive(@Param("id") UUID id, @Param("error") String error, @Param("now") java.time.Instant now);

    /** The stored attempt count, for the failure log. */
    @Query("select c.attempts from Campaign c where c.id = :id")
    Optional<Short> findAttemptsById(@Param("id") UUID id);

    /** A direct caller's flip to 'sending', only from the status its copy was loaded with. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Campaign c SET c.status='sending', c.updatedAt=:now WHERE c.id=:id AND c.status=:expected")
    int markSendingIf(@Param("id") UUID id, @Param("expected") String expected, @Param("now") java.time.Instant now);

    /** Row lock that serializes materialization of one campaign; also makes the claim's SKIP LOCKED pass it over. */
    @Query(value = "SELECT id FROM campaigns WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    List<UUID> lockForMaterialize(@Param("id") UUID id);

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

    /**
     * Org's campaigns created since a cutoff — feeds the marketing hub 30-day
     * attributed-purchases roll-up (summed per-campaign via CampaignAttributionService).
     */
    @Query("select c from Campaign c where c.orgId = :orgId and c.createdAt >= :since")
    List<Campaign> findByOrgCreatedSince(@Param("orgId") UUID orgId,
                                         @Param("since") java.time.Instant since);

    /** True when the org has a campaign of this origin in one of the given statuses. */
    boolean existsByOrgIdAndOriginAndStatusIn(UUID orgId, String origin, java.util.Collection<String> statuses);

    /** True when the org has a campaign of any origin in one of the given statuses. */
    boolean existsByOrgIdAndStatusIn(UUID orgId, java.util.Collection<String> statuses);

    /**
     * Spec §2.5: claim due campaigns — scheduled+due, retryable failed (attempts<3),
     * or stale sending (heartbeat > 5 min old, orphaned by a mid-send crash). SKIP LOCKED
     * so multiple dispatcher instances don't double-claim. Audience-plan campaigns are left
     * out while their sends switch is off, even if already scheduled, and while their org
     * lacks a legal name or legal contact (every origin while legalIdentityAllCampaigns is on),
     * so a held campaign never loops or eats the LIMIT.
     */
    @Query(value = """
        SELECT * FROM campaigns
        WHERE channel = 'email'
          AND (
            (status = 'scheduled' AND scheduled_at <= :now)
            OR (status = 'failed' AND attempts < 3)
            OR (status = 'sending' AND updated_at < :staleBefore)
          )
          AND (:audiencePlanSendsEnabled = TRUE OR origin <> 'audience_plan')
          AND ((origin <> 'audience_plan' AND :legalIdentityAllCampaigns = FALSE) OR EXISTS (
                SELECT 1 FROM organizations o
                WHERE o.id = campaigns.org_id
                  AND TRIM(COALESCE(o.legal_name, '')) <> ''
                  AND TRIM(COALESCE(o.legal_contact, '')) <> ''))
        ORDER BY scheduled_at NULLS FIRST
        LIMIT 10
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    java.util.List<com.imin.iminapi.marketing.model.Campaign> claimDue(
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            @org.springframework.data.repository.query.Param("staleBefore") java.time.Instant staleBefore,
            @org.springframework.data.repository.query.Param("audiencePlanSendsEnabled") boolean audiencePlanSendsEnabled,
            @org.springframework.data.repository.query.Param("legalIdentityAllCampaigns") boolean legalIdentityAllCampaigns);
}
