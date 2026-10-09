package com.imin.iminapi.repository;

import com.imin.iminapi.model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    Optional<Ticket> findByToken(String token);

    /**
     * Does this org hold any issued ticket? Gate on org deletion.
     *
     * <p>A subquery rather than a join because {@code Ticket} carries no
     * association to {@code Order} — only an {@code orderId} column — and no
     * {@code orgId} of its own. Strictly this is implied by
     * {@code OrderRepository.existsByOrgId} (a ticket cannot outlive its order's
     * FK), but the thing being protected is the buyer's ticket, so it is checked
     * for itself rather than inferred.
     */
    @Query("select count(t) > 0 from Ticket t "
           + "where t.orderId in (select o.id from Order o where o.orgId = :orgId)")
    boolean existsByOrgId(@Param("orgId") UUID orgId);
    List<Ticket> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    List<Ticket> findByOrderId(java.util.UUID orderId);

    /**
     * Batch fetch of tickets for many orders. The caller groups by
     * {@code orderId} to avoid N+1 queries when rendering listings. Order is
     * by createdAt asc within the result set (a single sort is fine — callers
     * partition by orderId themselves).
     */
    List<Ticket> findByOrderIdInOrderByOrderIdAscCreatedAtAsc(Collection<UUID> orderIds);

    /**
     * Per-tier sold aggregates for an event, over the SOLD set
     * ({@code state NOT IN ('refunded','revoked')}). Tuple shape:
     * {@code [UUID tierId, String tierName, Long sold, Long grossRevenueMinor, Long redeemed]}.
     * Grouped by {@code tierId} only, so each tier yields exactly ONE row even
     * when its name changed mid-sale ({@code tier_name} is a purchase-time
     * snapshot). {@code max(t.tierName)} is a representative display name, used
     * only as a fallback for genuinely-deleted tiers — current tiers display
     * their live name. Summed across rows, these give the headline tiles, so the
     * tier breakdown reconciles with the headline by construction.
     */
    @Query("""
            select t.tierId, max(t.tierName),
                   count(t),
                   coalesce(sum(t.priceMinor), 0),
                   coalesce(sum(case when t.state = 'redeemed' then 1 else 0 end), 0)
              from Ticket t
             where t.eventId = :eventId
               and t.state not in ('refunded', 'revoked')
             group by t.tierId
            """)
    List<Object[]> tierAggregates(@Param("eventId") UUID eventId);

    /**
     * Tickets this event lost to a chargeback: {@code revoked} and belonging to an order
     * with a dispute in one of {@code statuses}. Scoped through the disputes table rather
     * than by state alone so a future revoke path cannot quietly move the sold figure.
     */
    @Query("""
            select count(t) from Ticket t
             where t.eventId = :eventId
               and t.state = 'revoked'
               and t.orderId in (select d.orderId from com.imin.iminapi.dispute.Dispute d
                                  where d.eventId = :eventId
                                    and d.orderId is not null
                                    and d.status in :statuses)
            """)
    long countRevokedInDisputedOrders(
            @Param("eventId") UUID eventId,
            @Param("statuses") Collection<com.imin.iminapi.dispute.DisputeStatus> statuses);

    /** {@link #countRevokedInDisputedOrders} for a page of events: [eventId, count]. */
    @Query("""
            select t.eventId, count(t) from Ticket t
             where t.eventId in :eventIds
               and t.state = 'revoked'
               and t.orderId in (select d.orderId from com.imin.iminapi.dispute.Dispute d
                                  where d.eventId = t.eventId
                                    and d.orderId is not null
                                    and d.status in :statuses)
             group by t.eventId
            """)
    List<Object[]> countRevokedInDisputedOrdersByEventIds(
            @Param("eventIds") Collection<UUID> eventIds,
            @Param("statuses") Collection<com.imin.iminapi.dispute.DisputeStatus> statuses);

    /**
     * Every SOLD ticket for an event joined to its order, for the attendee CSV
     * export. Tuple shape: {@code [Ticket ticket, String buyerEmail, UUID orderId, Instant purchasedAt]}.
     * Never the order token: it is the buyer's bearer credential and the file leaves the platform.
     * Ordered oldest order first.
     */
    @Query("""
            select t, o.email, o.id, o.createdAt
              from Ticket t
              join com.imin.iminapi.model.Order o on o.id = t.orderId
             where t.eventId = :eventId
               and t.state not in ('refunded', 'revoked')
             order by o.createdAt asc, t.createdAt asc
            """)
    List<Object[]> attendeeRows(@Param("eventId") UUID eventId);

    /**
     * {@code created_at} of every SOLD ticket for an event since {@code since}, oldest
     * first. Feeds the Momentum suggestion's daily sold-per-day spark series
     * ({@code MomentumMetrics.dailySpark}).
     *
     * <p>SOLD set = {@code state not in ('refunded','revoked')} — the SAME predicate as
     * {@link #tierAggregates}, so the series reconciles with the sold figures elsewhere
     * rather than telling a second story.
     *
     * <p><b>Semantics, stated plainly:</b> {@code state} is MUTATED in place on refund
     * ({@code RefundService} sets {@code 'refunded'}), so a ticket bought on day D and
     * refunded later leaves day D's bucket retroactively. The series therefore reads
     * "tickets bought on day D that are STILL sold", not "gross tickets bought on day D".
     * That is net-of-refunds — the same semantics as {@code SUM(tier.sold)}, which is the
     * scalar the card shows next to the chart. There is no per-ticket refund timestamp to
     * do it any other way, and inventing one would be a fabrication.
     *
     * <p>Returns raw timestamps rather than a SQL {@code GROUP BY date(...)}: day bucketing
     * happens in Java, in the event's timezone; a SQL {@code date(...)} truncates in the
     * session zone instead. Row volume is bounded by one event's window (~10 days).
     */
    @Query("""
            select t.createdAt from Ticket t
             where t.eventId = :eventId
               and t.createdAt >= :since
               and t.state not in ('refunded', 'revoked')
             order by t.createdAt asc
            """)
    List<Instant> findSoldCreatedAtSince(@Param("eventId") UUID eventId,
                                          @Param("since") Instant since);

    List<Ticket> findByIdInAndOrderId(Collection<UUID> ids, UUID orderId);

    /**
     * SOLD tickets ({@code state not in ('refunded','revoked')}) on the org's orders created in
     * {@code [since, until)}, all modes. The org home's "tickets sold" for a window.
     */
    @Query("""
            select count(t) from Ticket t
              join com.imin.iminapi.model.Order o on o.id = t.orderId
             where o.orgId = :orgId
               and o.createdAt >= :since
               and o.createdAt < :until
               and t.state not in ('refunded', 'revoked')
            """)
    long countSoldByOrgInWindow(@Param("orgId") UUID orgId,
                                @Param("since") Instant since,
                                @Param("until") Instant until);

    /** SOLD tickets on an event, all modes; the org home's per-ticket denominator. */
    @Query("""
            select count(t) from Ticket t
             where t.eventId = :eventId
               and t.state not in ('refunded', 'revoked')
            """)
    long countSoldByEventId(@Param("eventId") UUID eventId);

    /** Ticket count for an event in a given state. Predictor finalize uses it for refund_count ('refunded'). */
    long countByEventIdAndState(UUID eventId, String state);

    /**
     * Latest {@code created_at} across an event's SOLD tickets ({@code state not in
     * ('refunded','revoked')}), or NULL when the event has sold nothing. The predictor
     * finalize job uses this as the "last seat sold" instant to derive time-to-sell-out
     * (publish → this) — only meaningful when the event actually sold out.
     */
    @Query("""
            select max(t.createdAt) from Ticket t
             where t.eventId = :eventId
               and t.state not in ('refunded', 'revoked')
            """)
    java.time.Instant findLastSoldCreatedAt(@Param("eventId") UUID eventId);

    /** Distinct event ids that have at least one SOLD ticket. Drives the sales-trajectory materialization + backfill. */
    @Query("""
            select distinct t.eventId from Ticket t
             where t.state not in ('refunded', 'revoked')
            """)
    List<UUID> findDistinctEventIdsWithSoldTickets();

    /**
     * (tierId, createdAt) for every SOLD ticket of an event, oldest first. The trajectory
     * materialization buckets these by calendar day in the event's timezone IN JAVA — never
     * a SQL {@code date(...)}, which truncates in the session zone. The SOLD predicate matches {@link #tierAggregates} so the daily series
     * reconciles with the per-tier sold figures.
     */
    @Query("""
            select t.tierId, t.createdAt from Ticket t
             where t.eventId = :eventId
               and t.state not in ('refunded', 'revoked')
             order by t.createdAt asc
            """)
    List<Object[]> findSoldTierAndCreatedAt(@Param("eventId") UUID eventId);

    /**
     * Atomic single-use redemption. Returns the number of rows updated:
     * 1 → fresh redemption; 0 → either already redeemed, revoked, or token unknown.
     * The caller selects the row again to disambiguate.
     */
    /**
     * {@code clearAutomatically = true} drops the cached Ticket entity from
     * the EntityManager after the UPDATE so a subsequent {@code findByToken}
     * re-reads the row with the freshly-redeemed columns rather than returning
     * the stale pre-update view.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update Ticket t
               set t.state = 'redeemed',
                   t.redeemedAt = :now,
                   t.redeemedByUserId = :userId
             where t.token = :token
               and t.state in ('issued', 'pre')
            """)
    int redeemAtomic(@Param("token") String token,
                      @Param("userId") UUID userId,
                      @Param("now") Instant now);

    /**
     * One row {@code [tickets, redeemed]}: tickets still held (not refunded or revoked) on paid orders
     * (Stripe, non-zero, live mode) for this org's events that ended before {@code now}.
     */
    @Query("""
            select count(t), coalesce(sum(case when t.state = 'redeemed' then 1 else 0 end), 0)
              from Ticket t, Order o, Event e
             where t.orderId = o.id
               and o.eventId = e.id
               and e.orgId = :orgId
               and o.orgId = :orgId
               and o.paymentMethod = 'stripe'
               and o.totalMinor > 0
               and o.testMode = false
               and t.state not in ('refunded', 'revoked')
               and coalesce(e.endsAt, e.startsAt) < :now
            """)
    List<Object[]> countShowUpForEndedPaidEvents(@Param("orgId") UUID orgId, @Param("now") java.time.Instant now);
}
