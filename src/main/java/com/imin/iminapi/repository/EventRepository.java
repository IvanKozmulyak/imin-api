package com.imin.iminapi.repository;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import org.springframework.data.domain.Page;
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
public interface EventRepository extends JpaRepository<Event, UUID> {

    @Query("SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
           "AND (:status IS NULL OR e.status = :status) ORDER BY e.startsAt DESC NULLS LAST, e.createdAt DESC")
    Page<Event> findVisibleByOrg(@Param("orgId") UUID orgId, @Param("status") EventStatus status, Pageable page);

    @Query("SELECT e FROM Event e WHERE e.id = :id AND e.deletedAt IS NULL")
    Optional<Event> findActive(@Param("id") UUID id);

    @Query(
        "SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.LIVE " +
        "AND e.startsAt > :now ORDER BY e.startsAt ASC")
    List<Event> findUpcomingLive(
            @Param("orgId") UUID orgId,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * Scannable events for the gate scanner: events for an org whose
     * {@code startsAt} is either in the future or within the last 24 hours
     * (so currently-running events remain visible until they clearly end).
     * Excludes soft-deleted events. Ordered by {@code startsAt} ascending so
     * the gate UI shows the most imminent event first. Events with a
     * {@code null} startsAt are excluded — the gate UX is built around
     * scheduled events and a null start has no meaningful "near now" answer.
     */
    @Query(
        "SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.startsAt IS NOT NULL AND e.startsAt >= :cutoff " +
        "ORDER BY e.startsAt ASC")
    List<Event> findScannableForGate(
            @Param("orgId") UUID orgId,
            @Param("cutoff") Instant cutoff);

    @Query(
        "SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.PAST " +
        "ORDER BY e.endsAt DESC")
    List<Event> findRecentPast(
            @Param("orgId") UUID orgId,
            Pageable pageable);

    @Query(
        "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.LIVE")
    long countLive(@Param("orgId") UUID orgId);

    @Query(
        "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.publishedAt IS NOT NULL")
    long countPublished(@Param("orgId") UUID orgId);

    @Query(
        "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.PAST")
    long countPast(@Param("orgId") UUID orgId);

    @Query(
        "SELECT COALESCE(SUM(e.revenueMinor), 0), COALESCE(SUM(e.sold), 0) " +
        "FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL")
    List<Object[]> sumRevenueAndSold(@Param("orgId") UUID orgId);

    @Query("""
        SELECT e FROM Event e
         WHERE e.id = :id
           AND e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
""")
    Optional<Event> findPublic(@Param("id") UUID id);

    @Query("""
        SELECT e FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND (CAST(:from AS timestamp) IS NULL
                OR e.startsAt >= :from
                OR (:includeOngoing = true AND (e.endsAt IS NULL OR e.endsAt > :now)))
           AND (CAST(:to AS timestamp) IS NULL OR e.startsAt < :to)
           AND (CAST(:genre AS string) IS NULL OR e.genre = :genre)
           AND (CAST(:type AS string) IS NULL OR e.type = :type)
           AND (CAST(:city AS string) IS NULL OR LOWER(e.venueCity) LIKE LOWER(CONCAT('%', CAST(:city AS string), '%')))
           AND (CAST(:country AS string) IS NULL OR e.venueCountry = :country)
           AND (CAST(:q AS string) IS NULL OR LOWER(e.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
           AND (CAST(:orgId AS java.util.UUID) IS NULL OR e.orgId = :orgId)
           AND (:onSaleOnly = false OR (
                  (e.onSaleAt IS NULL OR e.onSaleAt <= :now)
                  AND (e.saleClosesAt IS NULL OR e.saleClosesAt > :now)
                ))
         ORDER BY e.startsAt ASC NULLS LAST, e.id ASC
""")
    Page<Event> findPublicListing(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("genre") String genre,
            @Param("type") String type,
            @Param("city") String city,
            @Param("country") String country,
            @Param("q") String q,
            @Param("orgId") UUID orgId,
            @Param("onSaleOnly") boolean onSaleOnly,
            @Param("includeOngoing") boolean includeOngoing,
            @Param("now") Instant now,
            Pageable pageable);

    @Query("""
        SELECT DISTINCT e.venueCity, e.venueCountry FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND e.venueCity IS NOT NULL
           AND e.venueCity <> ''
         ORDER BY e.venueCity ASC, e.venueCountry ASC
""")
    List<Object[]> findDistinctPublicCities();

    /**
     * Bulk transition: LIVE events whose {@code endsAt} is in the past become PAST.
     * Events with {@code null endsAt} are excluded — no end date means the event has
     * no defined closing time and must remain LIVE indefinitely.
     *
     * <p>The JPQL explicitly sets {@code e.updatedAt = :updatedAt} because bulk UPDATE
     * bypasses JPA entity lifecycle callbacks ({@code @PreUpdate}) — without it,
     * ETag/concurrency consumers reading the event after the sweep would see a stale
     * timestamp and might incorrectly treat the record as unchanged.
     *
     * <p>{@code @Transactional} is on the repo method so the sweep tick (which has no
     * surrounding transaction) gets a clean boundary here — matching the pattern used
     * by {@code PromoCodeRepository.incrementUsedCount}.
     *
     * @param now       current instant used as the boundary for {@code endsAt < :now}
     * @param updatedAt value written to {@code updated_at} on every transitioned row
     * @return the number of rows updated
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        UPDATE Event e
           SET e.status = com.imin.iminapi.model.EventStatus.PAST,
               e.updatedAt = :updatedAt
         WHERE e.status = com.imin.iminapi.model.EventStatus.LIVE
           AND e.endsAt IS NOT NULL
           AND e.endsAt < :now
    """)
    int markLivePast(@Param("now") Instant now, @Param("updatedAt") Instant updatedAt);

    @Query("""
        SELECT DISTINCT e.genre FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND e.genre IS NOT NULL
           AND e.genre <> ''
         ORDER BY e.genre ASC
""")
    List<String> findDistinctPublicGenres();
}
