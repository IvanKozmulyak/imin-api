package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.AudiencePlan;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Collection;
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
     * Locks the event's non-superseded plans, newest first. Native plain FOR UPDATE, kept rather than the
     * dialect's PESSIMISTIC_WRITE, which renders FOR NO KEY UPDATE.
     */
    @Query(value = "SELECT * FROM audience_plans WHERE org_id = :orgId AND event_id = :eventId"
            + " AND superseded_by IS NULL ORDER BY created_at DESC FOR UPDATE", nativeQuery = true)
    List<AudiencePlan> lockCurrent(@Param("orgId") UUID orgId, @Param("eventId") UUID eventId);

    List<AudiencePlan> findByOrgIdAndEventIdOrderByCreatedAtAsc(UUID orgId, UUID eventId);

    /** Non-superseded plans of these events, newest first (the first row per event is its current plan). */
    @Query("SELECT p FROM AudiencePlan p WHERE p.orgId = :orgId AND p.eventId IN :eventIds"
            + " AND p.supersededBy IS NULL ORDER BY p.createdAt DESC")
    List<AudiencePlan> findCurrentForEvents(@Param("orgId") UUID orgId, @Param("eventIds") Collection<UUID> eventIds);

    /**
     * Postgres: transaction-scoped advisory lock keyed by the event, so first plans serialise without locking the
     * events row (a FOR UPDATE there would block every FK insert on the event, e.g. a checkout's order).
     */
    @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:eventId AS text), 0))) l",
            nativeQuery = true)
    long lockEventAdvisory(@Param("eventId") UUID eventId);
}
