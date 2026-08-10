package com.imin.iminapi.repository;

import com.imin.iminapi.model.NotifySubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface NotifySubscriptionRepository extends JpaRepository<NotifySubscription, UUID> {

    Optional<NotifySubscription> findByEventIdAndEmail(UUID eventId, String email);

    /**
     * Events that still owe at least one release email. Backed by the partial index from
     * V76 — once an event has been swept its rows leave the index, so the steady-state
     * scan is empty and costs nothing.
     */
    @Query("SELECT DISTINCT s.eventId FROM NotifySubscription s WHERE s.notifiedAt IS NULL")
    List<UUID> findEventIdsWithPendingSubscriptions();

    /** The un-notified subscribers of one event, oldest first. */
    @Query("SELECT s FROM NotifySubscription s WHERE s.eventId = :eventId AND s.notifiedAt IS NULL "
            + "ORDER BY s.createdAt ASC, s.id ASC")
    List<NotifySubscription> findPendingByEventId(@Param("eventId") UUID eventId);
}
