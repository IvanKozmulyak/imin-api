package com.imin.iminapi.repository;

import com.imin.iminapi.model.NotifySubscription;
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
     * The subscription's address holds a live ticket for its event: a non-test order with that
     * email (rows are lowercased on write, like {@code orders.email_normalized}) and a ticket on it
     * neither refunded nor revoked. Same rule as {@code MembershipRepository.findAttendedEventPairs}.
     */
    String HOLDS_TICKET = "SELECT o.id FROM com.imin.iminapi.model.Order o "
            + "WHERE o.eventId = s.eventId AND o.emailNormalized = s.email AND o.testMode = false "
            + "AND EXISTS (SELECT t.id FROM com.imin.iminapi.model.Ticket t "
            + "WHERE t.orderId = o.id AND t.state NOT IN ('refunded', 'revoked'))";

    /**
     * Stamps the delivery marker alone. A full-entity save of the sweep's copy would write back the
     * {@code every_release} and provenance a concurrent re-subscribe has just changed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE NotifySubscription s SET s.notifiedAt = :at WHERE s.id = :id")
    int markNotified(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Re-subscribe re-arm: clears the marker only if the row's last email, as stored now, is no later than
     * {@code cutoff}, so a mark the sweep committed after the caller read the row is never undone.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE NotifySubscription s SET s.notifiedAt = NULL WHERE s.id = :id AND s.notifiedAt <= :cutoff")
    int rearmIfNotifiedBy(@Param("id") UUID id, @Param("cutoff") Instant cutoff);

    /** Re-subscribe: the latest signup's consent provenance and {@code every_release}, never {@code notified_at}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE NotifySubscription s SET s.sourceIp = :sourceIp, s.userAgent = :userAgent, "
            + "s.consentText = :consentText, s.locale = :locale, s.everyRelease = :everyRelease WHERE s.id = :id")
    int refreshProvenance(@Param("id") UUID id, @Param("sourceIp") String sourceIp,
                          @Param("userAgent") String userAgent, @Param("consentText") String consentText,
                          @Param("locale") String locale, @Param("everyRelease") boolean everyRelease);

    /** Ids of the event's pending subscriptions whose address already holds a live ticket for it. */
    @Query("SELECT s.id FROM NotifySubscription s WHERE s.eventId = :eventId AND s.notifiedAt IS NULL "
            + "AND EXISTS (" + HOLDS_TICKET + ")")
    List<UUID> findPendingTicketHolderIds(@Param("eventId") UUID eventId);

    /**
     * A row the re-arm puts back to pending: emailed (or skipped) before {@code :cutoff}, signed up for every
     * release, and its address holds no live ticket for the event.
     */
    String REARMABLE = "s.notifiedAt IS NOT NULL AND s.notifiedAt < :cutoff AND s.everyRelease = true "
            + "AND NOT EXISTS (" + HOLDS_TICKET + ")";

    /**
     * LIVE, not deleted, PUBLIC, published, with an enabled tier and none with an unsold seat. Holds do not count,
     * so a checkout hold that lapses after a release never re-arms anyone.
     */
    String SOLD_OUT_EVENT = "e.deletedAt IS NULL AND e.status = com.imin.iminapi.model.EventStatus.LIVE "
            + "AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC AND e.publishedAt IS NOT NULL "
            + "AND EXISTS (SELECT tt.id FROM TicketTier tt WHERE tt.eventId = e.id AND tt.enabled = true) "
            + "AND NOT EXISTS (SELECT tu.id FROM TicketTier tu WHERE tu.eventId = e.id AND tu.enabled = true "
            + "AND tu.quantity - tu.sold > 0)";

    /** Sold-out events with at least one row to re-arm, so a sweep with nothing to change touches nothing. */
    @Query("SELECT DISTINCT s.eventId FROM NotifySubscription s, Event e WHERE e.id = s.eventId AND "
            + SOLD_OUT_EVENT + " AND " + REARMABLE)
    List<UUID> findEventIdsToRearm(@Param("cutoff") Instant cutoff);

    /** Puts the event's re-armable rows ({@link #REARMABLE}) back to pending. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE NotifySubscription s SET s.notifiedAt = NULL WHERE s.eventId = :eventId AND " + REARMABLE)
    int rearmWithoutTicket(@Param("eventId") UUID eventId, @Param("cutoff") Instant cutoff);

    /** Subscriptions of the event still owed the next release email. */
    @Query("SELECT COUNT(s) FROM NotifySubscription s WHERE s.eventId = :eventId AND s.notifiedAt IS NULL")
    long countPendingByEventId(@Param("eventId") UUID eventId);

    /** Per event of the page: [eventId, pending count]; events with none have no row. */
    @Query("SELECT s.eventId, COUNT(s) FROM NotifySubscription s WHERE s.eventId IN :eventIds "
            + "AND s.notifiedAt IS NULL GROUP BY s.eventId")
    List<Object[]> countPendingByEventIds(@Param("eventIds") java.util.Collection<UUID> eventIds);

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
     * parameter non-null side-steps the Postgres {@code lower(bytea)} null-String trap).
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
     * Postgres {@code lower(bytea)} null-String trap out of reach.
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
     * is left to the caller — the natural order is un-notified first, and it
     * is done in Java rather than with {@code NULLS FIRST} in the dialect.
     *
     * <p>Compares the column directly rather than wrapping it in {@code LOWER}:
     * rows are lowercased on write and the caller passes
     * {@code email_normalized}, so both sides are already lower case — and a
     * {@code LOWER(...)} wrapper would make {@code ix_notify_subscriptions_email}
     * (V89) unusable, which is the whole reason the index exists.
     */
    @Query("SELECT s FROM NotifySubscription s WHERE s.email IN :emails")
    List<NotifySubscription> findByEmailIn(@Param("emails") java.util.Collection<String> emails);

    /** "Stop watching" — removes the alert for every address the account holds. */
    @Modifying
    @Transactional
    @Query("DELETE FROM NotifySubscription s WHERE s.eventId = :eventId AND s.email IN :emails")
    int deleteByEventIdAndEmailIn(@Param("eventId") UUID eventId,
                                  @Param("emails") java.util.Collection<String> emails);
}
