package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.MomentumFloorSkip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface MomentumFloorSkipRepository extends JpaRepository<MomentumFloorSkip, UUID> {

    /** Records the event's skipped moment, replacing the one an earlier pass wrote. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO momentum_floor_skips (event_id, org_id, trigger_type, sendable, floor, checked_at) "
            + "VALUES (:eventId, :orgId, :triggerType, :sendable, :floor, :checkedAt) "
            + "ON CONFLICT (event_id) DO UPDATE SET org_id = EXCLUDED.org_id, trigger_type = EXCLUDED.trigger_type, "
            + "sendable = EXCLUDED.sendable, floor = EXCLUDED.floor, checked_at = EXCLUDED.checked_at",
            nativeQuery = true)
    int upsert(@Param("eventId") UUID eventId, @Param("orgId") UUID orgId, @Param("triggerType") String triggerType,
               @Param("sendable") int sendable, @Param("floor") int floor, @Param("checkedAt") Instant checkedAt);

    /** Clears the event's skip once a pass ends without one. */
    @Modifying
    @Transactional
    @Query("DELETE FROM MomentumFloorSkip s WHERE s.eventId = :eventId")
    int deleteByEventId(@Param("eventId") UUID eventId);

    /** The org's skips written after {@code since}. */
    List<MomentumFloorSkip> findByOrgIdAndCheckedAtAfter(UUID orgId, Instant since);
}
