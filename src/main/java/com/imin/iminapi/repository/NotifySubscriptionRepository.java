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

    /**
     * Buyer-initiated Art.17 erasure (§7.2 step 3): drop notify-me rows for the
     * account's whole address set, <b>across every org</b>.
     *
     * <p>Deliberately unscoped, and deliberately not a loop over
     * {@link #deleteByOrgIdAndEmail}. The org scope on that method is correct for
     * an <i>organizer</i> DSAR — one controller erasing its own copy — but here
     * the data subject is erasing themselves platform-wide, and a notify
     * subscription is the buyer's own standing request for mail, not an
     * organizer's audience record. Two consequences follow:
     *
     * <ul>
     *   <li>Rows in orgs the buyer never bought from are still theirs, and
     *       {@code executeErase} would never reach them: it only iterates the
     *       orgs that hold a membership.</li>
     *   <li>A notify subscription can exist with <b>no membership at all</b> —
     *       anyone can subscribe to a release without buying — so the
     *       membership-driven cascade has no hook for it whatsoever.</li>
     * </ul>
     *
     * <p>{@code email} is NOT NULL in the column and lowercased on write;
     * callers pass non-null normalized addresses, which also keeps the
     * H2-vs-Postgres {@code lower(bytea)} null-String trap out of reach.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM NotifySubscription s WHERE LOWER(s.email) IN :emails")
    int deleteByEmailIn(@Param("emails") java.util.Collection<String> emails);

    // ── Buyer accounts: drop alerts on the account page (spec §4.5) ─────────

    /**
     * Every drop alert held by these addresses.
     *
     * <p>Same boundary as {@code GET /buyer/orders}: the caller passes the
     * account's <b>verified</b> normalized addresses and nothing else. Sorting
     * is left to the caller — the natural order is un-notified first, and
     * {@code NULLS FIRST} is not portable across H2 and Postgres, so it is done
     * in Java rather than fought with in the dialect.
     */
    @Query("SELECT s FROM NotifySubscription s WHERE LOWER(s.email) IN :emails")
    List<NotifySubscription> findByEmailIn(@Param("emails") java.util.Collection<String> emails);

    /** "Stop watching" — removes the alert for every address the account holds. */
    @Modifying
    @Transactional
    @Query("DELETE FROM NotifySubscription s WHERE s.eventId = :eventId AND LOWER(s.email) IN :emails")
    int deleteByEventIdAndEmailIn(@Param("eventId") UUID eventId,
                                  @Param("emails") java.util.Collection<String> emails);
}
