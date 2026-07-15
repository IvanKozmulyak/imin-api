package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.MomentumSuggestion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

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
}
