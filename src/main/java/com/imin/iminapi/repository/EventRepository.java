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
}
