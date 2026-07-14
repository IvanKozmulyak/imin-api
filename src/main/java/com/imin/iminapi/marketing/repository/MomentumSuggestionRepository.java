package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.MomentumSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

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
}
