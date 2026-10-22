package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.StockAlert;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stock alerts: one row per (event, tier, kind); every status change is a conditional UPDATE on the status it leaves. */
@RepositoryRestResource(exported = false)
public interface StockAlertRepository extends JpaRepository<StockAlert, UUID> {

    /** A new ready alert; 0 when one already exists for the tier and kind, in any status. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO stock_alerts (id, org_id, event_id, tier_id, kind, status, facts_json, "
            + "reach_at_prepare, locale, created_at) VALUES (:id, :orgId, :eventId, :tierId, :kind, 'ready', "
            + ":factsJson, :reach, :locale, :now) ON CONFLICT (event_id, tier_id, kind) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("eventId") UUID eventId,
                       @Param("tierId") UUID tierId, @Param("kind") String kind, @Param("factsJson") String factsJson,
                       @Param("reach") int reach, @Param("locale") String locale, @Param("now") Instant now);

    boolean existsByEventIdAndTierIdAndKind(UUID eventId, UUID tierId, String kind);

    List<StockAlert> findByEventIdOrderByCreatedAtDesc(UUID eventId);

    Optional<StockAlert> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<StockAlert> findByCampaignId(UUID campaignId);

    /** Ready alerts of every org, oldest first. */
    @Query("SELECT a FROM StockAlert a WHERE a.status = 'ready' ORDER BY a.createdAt ASC")
    List<StockAlert> findReady(Pageable page);

    /** ready→drafted with its draft campaign and the facts read at review. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE stock_alerts SET status = 'drafted', campaign_id = :campaignId, facts_json = :factsJson, "
            + "acted_at = :now, acted_by = :actor WHERE id = :id AND status = 'ready'", nativeQuery = true)
    int markDrafted(@Param("id") UUID id, @Param("campaignId") UUID campaignId, @Param("factsJson") String factsJson,
                    @Param("now") Instant now, @Param("actor") UUID actor);

    /** ready→dismissed ("Not this time"): it never comes back for the tier and kind. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE stock_alerts SET status = 'dismissed', acted_at = :now, acted_by = :actor "
            + "WHERE id = :id AND status = 'ready'", nativeQuery = true)
    int markDismissed(@Param("id") UUID id, @Param("now") Instant now, @Param("actor") UUID actor);

    /** ready→expired with the reason its condition stopped holding. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE stock_alerts SET status = 'expired', expired_reason = :reason, acted_at = :now "
            + "WHERE id = :id AND status = 'ready'", nativeQuery = true)
    int markExpired(@Param("id") UUID id, @Param("reason") String reason, @Param("now") Instant now);

    /** drafted→withdrawn by the send guard, with the fact that changed. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE stock_alerts SET status = 'withdrawn', withdrawn_reason = :reason, acted_at = :now "
            + "WHERE id = :id AND status = 'drafted'", nativeQuery = true)
    int markWithdrawn(@Param("id") UUID id, @Param("reason") String reason, @Param("now") Instant now);

    /** Freezes what every recipient's email quotes; a resumed drive finds it set and keeps it. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE stock_alerts SET facts_at_send_json = :factsJson "
            + "WHERE id = :id AND status = 'drafted' AND facts_at_send_json IS NULL", nativeQuery = true)
    int recordFactsAtSendIfNull(@Param("id") UUID id, @Param("factsJson") String factsJson);

    /** A ready alert's facts as read on this pass, so the card shows today's numbers. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE stock_alerts SET facts_json = :factsJson WHERE id = :id AND status = 'ready'",
            nativeQuery = true)
    int refreshReadyFacts(@Param("id") UUID id, @Param("factsJson") String factsJson);
}
