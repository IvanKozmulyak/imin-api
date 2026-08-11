package com.imin.iminapi.repository;

import com.imin.iminapi.model.NotifySubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * DSAR erasure (Art.17): drop the erasing org's notify-me rows for an erased buyer's
     * address. Called from {@code DsarService.executeErase}.
     *
     * <p>Notify subscriptions sit outside the consumer/membership graph — they are keyed by
     * (event, raw email) and carry no org column — so the audience cascade never reached them
     * and an "erased" buyer's address survived here, still owed a release email. The org scope
     * comes from the parent event: another org's subscription for the same address is that
     * org's data and must be left alone (DSAR is org-scoped).
     *
     * <p>{@code email} is NOT NULL in the column and lowercased on write; callers must pass a
     * non-null normalized address (the {@code LOWER()} is belt-and-braces, and keeping the
     * parameter non-null side-steps the H2-vs-Postgres {@code lower(bytea)} null-String trap).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM NotifySubscription s WHERE LOWER(s.email) = :email "
            + "AND s.eventId IN (SELECT e.id FROM Event e WHERE e.orgId = :orgId)")
    int deleteByOrgIdAndEmail(@Param("orgId") UUID orgId, @Param("email") String email);
}
