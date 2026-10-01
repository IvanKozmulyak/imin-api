package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.DateCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Date checks, newest first per org. */
@RepositoryRestResource(exported = false)
public interface DateCheckRepository extends JpaRepository<DateCheck, UUID> {

    /** The org's checks of one origin, newest first. */
    List<DateCheck> findByOrgIdAndOriginOrderByCreatedAtDesc(UUID orgId, String origin, Pageable pageable);

    /** The most recently scored check made for an event ({@code date_check.event_id}). */
    Optional<DateCheck> findFirstByOrgIdAndEventIdOrderByUpdatedAtDescCreatedAtDescIdDesc(UUID orgId, UUID eventId);

    /** The event's checks with a candidate date in [from, to], most recently scored first. */
    @Query("SELECT c FROM DateCheck c WHERE c.orgId = :orgId AND c.eventId = :eventId AND EXISTS ("
            + "SELECT 1 FROM DateCheckDate d WHERE d.dateCheckId = c.id AND d.candidateDate BETWEEN :from AND :to) "
            + "ORDER BY c.updatedAt DESC, c.createdAt DESC, c.id DESC")
    List<DateCheck> findEventChecksWithDateBetween(@Param("orgId") UUID orgId, @Param("eventId") UUID eventId,
                                                   @Param("from") LocalDate from, @Param("to") LocalDate to,
                                                   Pageable pageable);

    /** Links a check to an event unless it names one; a bulk update, so updated_at is not bumped. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE DateCheck d SET d.eventId = :eventId WHERE d.id = :id AND d.eventId IS NULL")
    int linkEventIfUnset(@Param("id") UUID id, @Param("eventId") UUID eventId);

    /** The event's radar runs, newest first. */
    @Query("SELECT c FROM DateCheck c WHERE c.orgId = :orgId AND c.eventId = :eventId AND c.origin = 'radar' "
            + "ORDER BY c.createdAt DESC, c.id DESC")
    List<DateCheck> findRadarRuns(@Param("orgId") UUID orgId, @Param("eventId") UUID eventId, Pageable page);

    /** The run's own verdict and risk, once, right after it is scored; bulk JPQL, since the columns are insert-only. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE DateCheck c SET c.radarVerdict = :verdict, c.radarRisk = :risk "
            + "WHERE c.id = :id AND c.origin = 'radar' AND c.radarVerdict IS NULL")
    int recordRadarResult(@Param("id") UUID id, @Param("verdict") String verdict, @Param("risk") Short risk);

    /**
     * Serialises re-scores of one check: its dates are replaced under a unique (check, date) key. Native
     * {@code FOR UPDATE}: PESSIMISTIC_WRITE renders {@code FOR NO KEY UPDATE}, which H2 cannot parse.
     */
    @Query(value = "SELECT * FROM date_check WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<DateCheck> findLockedById(@Param("id") UUID id);
}
