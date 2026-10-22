package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.MomentumSuggestion;
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

@RepositoryRestResource(exported = false)
public interface MomentumSuggestionRepository extends JpaRepository<MomentumSuggestion, UUID> {

    /** The single live suggestion for an (event, trigger), if any — app-layer uniqueness check. */
    Optional<MomentumSuggestion> findByEventIdAndTriggerTypeAndStatus(
            UUID eventId, String triggerType, String status);

    /** Most recent suggestion of any status for cooldown evaluation. */
    Optional<MomentumSuggestion> findTopByEventIdAndTriggerTypeOrderBySuggestedAtDesc(
            UUID eventId, String triggerType);

    /** Org-scoped list for the Momentum tab, newest first. */
    List<MomentumSuggestion> findByOrgIdAndStatusOrderBySuggestedAtDesc(UUID orgId, String status);

    /** Live suggestion for an event (Momentum sales-dashboard card), regardless of trigger. */
    List<MomentumSuggestion> findByEventIdAndStatus(UUID eventId, String status);

    /** Whether the event has a suggestion in {@code status} under any trigger; clicked_no_buy stands down on one. */
    boolean existsByEventIdAndStatus(UUID eventId, String status);

    /** Whether the event ever had a suggestion of this trigger, in any status. */
    boolean existsByEventIdAndTriggerType(UUID eventId, String triggerType);

    /** Live 'suggested' rows for the expiry sweep. */
    List<MomentumSuggestion> findByStatus(String status);

    /**
     * Momentum-engine-state counter: the org's suggestions in a terminal {@code status}
     * (approved/dismissed) whose {@code actedAt} falls in the trailing window
     * ({@code actedAt > since}). Feeds {@code approved30d} / {@code dismissed30d}.
     */
    long countByOrgIdAndStatusAndActedAtAfter(UUID orgId, String status, Instant since);

    /**
     * Activity-log rows for the Momentum tab: the org's suggestions that are NOT still
     * live ({@code status <> 'suggested'}), newest first by when they were acted on
     * (falling back to when they were suggested for any row missing {@code actedAt}).
     * The caller limits to the 10 most recent via a {@link Pageable}.
     */
    @Query("SELECT s FROM MomentumSuggestion s WHERE s.orgId = :orgId AND s.status <> 'suggested' " +
           "ORDER BY COALESCE(s.actedAt, s.suggestedAt) DESC")
    List<MomentumSuggestion> findRecentActedByOrg(@Param("orgId") UUID orgId, Pageable page);

    // The three writers of a suggestion's end state; each moves only a live row, so none can revert another.

    /** Claims a live suggestion for its draft; 0 when it was already approved, dismissed or expired. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE MomentumSuggestion s SET s.status = 'approved', s.campaignId = :campaignId, s.actedAt = :now "
           + "WHERE s.id = :id AND s.orgId = :orgId AND s.status = 'suggested'")
    int approveIfLive(@Param("id") UUID id, @Param("orgId") UUID orgId,
                      @Param("campaignId") UUID campaignId, @Param("now") Instant now);

    /** Dismisses a live suggestion; 0 when it was no longer live. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE MomentumSuggestion s SET s.status = 'dismissed', s.actedAt = :now "
           + "WHERE s.id = :id AND s.orgId = :orgId AND s.status = 'suggested'")
    int dismissIfLive(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("now") Instant now);

    /** Expires a live suggestion; 0 when an approval or dismissal got there first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE MomentumSuggestion s SET s.status = 'expired', s.actedAt = :now "
           + "WHERE s.id = :id AND s.status = 'suggested'")
    int expireIfLive(@Param("id") UUID id, @Param("now") Instant now);
}
