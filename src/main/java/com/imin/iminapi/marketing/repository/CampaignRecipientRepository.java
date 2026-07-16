package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.CampaignRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

// Internal store — never exposed over Spring Data REST (auto-export both leaks
// the table into the public OpenAPI and crashes springdoc on ambiguous
// overloaded search mappings).
@RepositoryRestResource(exported = false)
public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, UUID> {

    List<CampaignRecipient> findByCampaignIdAndStatus(UUID campaignId, String status);

    java.util.Optional<CampaignRecipient> findByProviderMessageId(String providerMessageId);

    // ---- recipient log paging (GET /marketing/campaigns/{id}/recipients) ----
    // Status and engagement are ORTHOGONAL axes — a row can be `delivered` AND opened — so the
    // filter combinations are spelled out as distinct derived queries rather than one @Query with
    // nullable :status / :engagement params. That is deliberate on two counts:
    //   1. A nullable String param bound into a SQL function is the H2-vs-Postgres trap that bit
    //      MembershipRepository: Hibernate binds null as bytea and Postgres rejects it, while H2
    //      happily passes. Derived queries bind no param at all on the branches that don't need it.
    //   2. Each method name below is UNIQUE — no new overloaded finders, and notably no paged
    //      `findByCampaignIdAndStatus(id, status, Pageable)` twin of the finder above: the log's
    //      status filter goes through findByCampaignIdAndStatusIn (a single value is just a
    //      one-element list). Overloaded search mappings on THIS repository once crashed springdoc
    //      and took /v3/api-docs down in prod.
    // Callers must pass a Pageable with a deterministic Sort (CampaignService sorts by id) —
    // Postgres gives no row order without an ORDER BY, so paging would otherwise skip/duplicate.

    java.util.List<CampaignRecipient> findByCampaignId(UUID campaignId, org.springframework.data.domain.Pageable pageable);

    java.util.List<CampaignRecipient> findByCampaignIdAndOpenedAtNotNull(UUID campaignId, org.springframework.data.domain.Pageable pageable);

    java.util.List<CampaignRecipient> findByCampaignIdAndClickedAtNotNull(UUID campaignId, org.springframework.data.domain.Pageable pageable);

    java.util.List<CampaignRecipient> findByCampaignIdAndStatusIn(UUID campaignId, java.util.Collection<String> statuses, org.springframework.data.domain.Pageable pageable);

    java.util.List<CampaignRecipient> findByCampaignIdAndStatusInAndOpenedAtNotNull(UUID campaignId, java.util.Collection<String> statuses, org.springframework.data.domain.Pageable pageable);

    java.util.List<CampaignRecipient> findByCampaignIdAndStatusInAndClickedAtNotNull(UUID campaignId, java.util.Collection<String> statuses, org.springframework.data.domain.Pageable pageable);

    // ---- `total` for the active filter: one real aggregate per find-branch above ----

    long countByCampaignIdAndStatus(UUID campaignId, String status);

    long countByCampaignIdAndStatusIn(UUID campaignId, java.util.Collection<String> statuses);

    long countByCampaignIdAndOpenedAtNotNull(UUID campaignId);

    long countByCampaignIdAndClickedAtNotNull(UUID campaignId);

    long countByCampaignIdAndStatusInAndOpenedAtNotNull(UUID campaignId, java.util.Collection<String> statuses);

    long countByCampaignIdAndStatusInAndClickedAtNotNull(UUID campaignId, java.util.Collection<String> statuses);

    long countByCampaignId(UUID campaignId);

    /**
     * Whole-log chip counts in ONE pass over the campaign's rows — conditional aggregation rather
     * than a count query per chip, so a large campaign is scanned once instead of eight times.
     * Served by the existing {@code ix_campaign_recipients_status (campaign_id, status)} index on
     * its leading column; {@code opened_at}/{@code clicked_at} are read from the heap during the
     * same scan, so no extra index is warranted.
     *
     * <p>{@code opened}/{@code clicked} come from the timestamps, NOT the status — engagement is
     * orthogonal to lifecycle, and the {@code RecipientStatus} enum deliberately has no
     * opened/clicked member. The buckets do not sum to {@code total}.
     *
     * <p>{@code coalesce(..., 0L)} guards the empty-campaign case, where {@code sum()} is NULL and
     * would otherwise blow up unboxing into the record's primitive {@code long} fields.
     */
    @Query("""
            select new com.imin.iminapi.marketing.dto.RecipientCounts(
                       count(r),
                       coalesce(sum(case when r.openedAt is not null then 1L else 0L end), 0L),
                       coalesce(sum(case when r.clickedAt is not null then 1L else 0L end), 0L),
                       coalesce(sum(case when r.status = 'skipped' then 1L else 0L end), 0L),
                       coalesce(sum(case when r.status = 'bounced' then 1L else 0L end), 0L),
                       coalesce(sum(case when r.status = 'failed' then 1L else 0L end), 0L),
                       coalesce(sum(case when r.status = 'complained' then 1L else 0L end), 0L),
                       coalesce(sum(case when r.status = 'unsubscribed' then 1L else 0L end), 0L))
              from CampaignRecipient r
             where r.campaignId = :campaignId
            """)
    com.imin.iminapi.marketing.dto.RecipientCounts chipCounts(@Param("campaignId") UUID campaignId);

    /**
     * Bulk-delete every recipient row for a campaign. Called before the campaign row is
     * hard-deleted (draft-delete, spec Task B3) so the FK order is safe regardless of the
     * DB-level ON DELETE CASCADE. Drafts should have none — this is defensive.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM CampaignRecipient r WHERE r.campaignId = :campaignId")
    void deleteByCampaignId(@Param("campaignId") UUID campaignId);

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
     * Rolling-window org send count — backs the per-org daily cap in the dispatcher
     * (spec §7). Joins recipients to their campaign by org, counting rows actually
     * sent (or further along) with a send timestamp inside the window.
     */
    @Query("""
            select count(r) from CampaignRecipient r, com.imin.iminapi.marketing.model.Campaign c
             where r.campaignId = c.id
               and c.orgId = :orgId
               and r.status in ('sent','delivered','opened','clicked')
               and r.lastEventAt >= :since
            """)
    long countRecentSendsForOrg(@Param("orgId") UUID orgId,
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
