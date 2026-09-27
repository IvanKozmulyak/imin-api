package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.AudiencePlan;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AudiencePlanRepository extends Repository<AudiencePlan, UUID> {

    AudiencePlan save(AudiencePlan plan);

    Optional<AudiencePlan> findById(UUID id);

    /** The current plan: newest non-superseded row of this org's event. */
    Optional<AudiencePlan> findFirstByOrgIdAndEventIdAndSupersededByIsNullOrderByCreatedAtDesc(UUID orgId, UUID eventId);

    /**
     * Locks the event's non-superseded plans, newest first. Native plain FOR UPDATE: the dialect's
     * PESSIMISTIC_WRITE renders FOR NO KEY UPDATE, which H2 rejects.
     */
    @Query(value = "SELECT * FROM audience_plans WHERE org_id = :orgId AND event_id = :eventId"
            + " AND superseded_by IS NULL ORDER BY created_at DESC FOR UPDATE", nativeQuery = true)
    List<AudiencePlan> lockCurrent(@Param("orgId") UUID orgId, @Param("eventId") UUID eventId);

    List<AudiencePlan> findByOrgIdAndEventIdOrderByCreatedAtAsc(UUID orgId, UUID eventId);
}
