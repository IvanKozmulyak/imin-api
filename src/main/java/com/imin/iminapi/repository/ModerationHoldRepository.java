package com.imin.iminapi.repository;

import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.ModerationHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Staff measures against an organizer (V189). The two predicates are the enforcement points' only
 * question: is an active hold with that measure in scope, i.e. on the whole org or on this event.
 * The UPDATEs are guarded by {@code IS NULL}, so a second lift or a second notice stamp writes nothing.
 */
@RepositoryRestResource(exported = false)
public interface ModerationHoldRepository extends JpaRepository<ModerationHold, UUID> {

    @Query("""
        SELECT COUNT(h) > 0 FROM ModerationHold h
         WHERE h.orgId = :orgId
           AND h.liftedAt IS NULL
           AND h.suspendSales = true
           AND (h.eventId IS NULL OR h.eventId = :eventId)
        """)
    boolean salesSuspended(@Param("orgId") UUID orgId, @Param("eventId") UUID eventId);

    @Query("""
        SELECT COUNT(h) > 0 FROM ModerationHold h
         WHERE h.orgId = :orgId
           AND h.liftedAt IS NULL
           AND h.holdPayouts = true
           AND (h.eventId IS NULL OR h.eventId = :eventId)
        """)
    boolean payoutsHeld(@Param("orgId") UUID orgId, @Param("eventId") UUID eventId);

    /** Closes one hold; 0 rows when it was already lifted, so the first lifter is kept. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE moderation_holds
           SET lifted_at = :at, lifted_by = :by, lifted_by_email = :email, lift_note = :note
         WHERE id = :id AND lifted_at IS NULL
        """, nativeQuery = true)
    int lift(@Param("id") UUID id, @Param("by") UUID by, @Param("email") String email,
             @Param("note") String note, @Param("at") Instant at);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE moderation_holds SET notice_sent_at = :at WHERE id = :id AND notice_sent_at IS NULL",
            nativeQuery = true)
    int stampNotice(@Param("id") UUID id, @Param("at") Instant at);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE moderation_holds SET lift_notice_sent_at = :at
         WHERE id = :id AND lifted_at IS NOT NULL AND lift_notice_sent_at IS NULL
        """, nativeQuery = true)
    int stampLiftNotice(@Param("id") UUID id, @Param("at") Instant at);

    @Query("SELECT h FROM ModerationHold h WHERE h.orgId = :orgId AND h.liftedAt IS NULL ORDER BY h.placedAt DESC, h.id")
    List<ModerationHold> findActiveByOrgId(@Param("orgId") UUID orgId);

    List<ModerationHold> findByOrgIdOrderByPlacedAtDesc(UUID orgId);

    @Query("SELECT COUNT(h) FROM ModerationHold h WHERE h.orgId = :orgId AND h.liftedAt IS NULL")
    long countActiveByOrgId(@Param("orgId") UUID orgId);

    /** Payout runs of the org Stripe may already be moving: an org-wide hold cannot stop them. */
    @Query(value = """
        SELECT COUNT(*) FROM payout_runs r
         WHERE r.org_id = :orgId AND r.status IN ('planned', 'submitted', 'retrying')
        """, nativeQuery = true)
    long countPayoutsInFlightForOrg(@Param("orgId") UUID orgId);

    /** Payout runs of one event Stripe may already be moving: an event hold cannot stop them. */
    @Query(value = """
        SELECT COUNT(*) FROM payout_runs r
         WHERE r.org_id = :orgId AND r.event_id = :eventId AND r.status IN ('planned', 'submitted', 'retrying')
        """, nativeQuery = true)
    long countPayoutsInFlightForEvent(@Param("orgId") UUID orgId, @Param("eventId") UUID eventId);

    /** Org campaigns that will send once due now that no hold covers them: the dispatcher's own predicates. */
    @Query(value = "SELECT COUNT(*) FROM campaigns WHERE campaigns.org_id = :orgId AND" + CampaignRepository.WAITING
            + "AND " + CampaignRepository.SENDABLE, nativeQuery = true)
    long countReleasedCampaignsForOrg(@Param("orgId") UUID orgId,
                                      @Param("audiencePlanSendsEnabled") boolean audiencePlanSendsEnabled,
                                      @Param("legalIdentityAllCampaigns") boolean legalIdentityAllCampaigns);

    /** Same, limited to the campaigns of one event. */
    @Query(value = "SELECT COUNT(*) FROM campaigns WHERE campaigns.org_id = :orgId AND campaigns.event_id = :eventId AND"
            + CampaignRepository.WAITING + "AND " + CampaignRepository.SENDABLE, nativeQuery = true)
    long countReleasedCampaignsForEvent(@Param("orgId") UUID orgId, @Param("eventId") UUID eventId,
                                        @Param("audiencePlanSendsEnabled") boolean audiencePlanSendsEnabled,
                                        @Param("legalIdentityAllCampaigns") boolean legalIdentityAllCampaigns);
}
