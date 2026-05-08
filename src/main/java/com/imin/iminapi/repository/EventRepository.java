package com.imin.iminapi.repository;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface EventRepository extends JpaRepository<Event, UUID> {

    @Query("SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
           "AND (:status IS NULL OR e.status = :status) ORDER BY e.startsAt DESC NULLS LAST, e.createdAt DESC")
    Page<Event> findVisibleByOrg(@Param("orgId") UUID orgId, @Param("status") EventStatus status, Pageable page);

    @Query("SELECT e FROM Event e WHERE e.id = :id AND e.deletedAt IS NULL")
    Optional<Event> findActive(@Param("id") UUID id);

    @org.springframework.data.jpa.repository.Query(
        "SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.LIVE " +
        "AND e.startsAt > :now ORDER BY e.startsAt ASC")
    java.util.List<com.imin.iminapi.model.Event> findUpcomingLive(
            @org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
        "SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.PAST " +
        "ORDER BY e.endsAt DESC")
    java.util.List<com.imin.iminapi.model.Event> findRecentPast(
            @org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.LIVE")
    long countLive(@org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId);

    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.publishedAt IS NOT NULL")
    long countPublished(@org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId);

    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.PAST")
    long countPast(@org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId);

    @org.springframework.data.jpa.repository.Query(
        "SELECT COALESCE(SUM(e.revenueMinor), 0), COALESCE(SUM(e.sold), 0) " +
        "FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL")
    java.util.List<Object[]> sumRevenueAndSold(@org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT e FROM Event e
         WHERE e.id = :id
           AND e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
""")
    java.util.Optional<com.imin.iminapi.model.Event> findPublic(@org.springframework.data.repository.query.Param("id") java.util.UUID id);

    @org.springframework.data.jpa.repository.Query("""
        SELECT e FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND (:from IS NULL OR e.startsAt >= :from)
           AND (:to IS NULL OR e.startsAt < :to)
           AND (:genre IS NULL OR e.genre = :genre)
           AND (:type IS NULL OR e.type = :type)
           AND (:city IS NULL OR LOWER(e.venueCity) LIKE LOWER(CONCAT('%', :city, '%')))
           AND (:country IS NULL OR e.venueCountry = :country)
           AND (:q IS NULL OR LOWER(e.name) LIKE LOWER(CONCAT('%', :q, '%')))
           AND (:orgId IS NULL OR e.orgId = :orgId)
           AND (:onSaleOnly = false OR (
                  (e.onSaleAt IS NULL OR e.onSaleAt <= :now)
                  AND (e.saleClosesAt IS NULL OR e.saleClosesAt > :now)
                ))
         ORDER BY e.startsAt ASC NULLS LAST, e.id ASC
""")
    org.springframework.data.domain.Page<com.imin.iminapi.model.Event> findPublicListing(
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to,
            @org.springframework.data.repository.query.Param("genre") String genre,
            @org.springframework.data.repository.query.Param("type") String type,
            @org.springframework.data.repository.query.Param("city") String city,
            @org.springframework.data.repository.query.Param("country") String country,
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId,
            @org.springframework.data.repository.query.Param("onSaleOnly") boolean onSaleOnly,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            org.springframework.data.domain.Pageable pageable);
}
