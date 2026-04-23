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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface EventRepository extends JpaRepository<Event, UUID> {

    @Query("SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
           "AND (:status IS NULL OR e.status = :status) ORDER BY e.startsAt DESC NULLS LAST, e.createdAt DESC")
    Page<Event> findVisibleByOrg(@Param("orgId") UUID orgId, @Param("status") EventStatus status, Pageable page);

    @Query("SELECT e FROM Event e WHERE e.id = :id AND e.deletedAt IS NULL")
    Optional<Event> findActive(@Param("id") UUID id);

    @Modifying
    @Query("DELETE FROM Event e WHERE e.status = com.imin.iminapi.model.EventStatus.DRAFT " +
           "AND e.name = '' AND e.createdAt < :cutoff")
    int deleteEmptyDraftsOlderThan(@Param("cutoff") Instant cutoff);

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
}
