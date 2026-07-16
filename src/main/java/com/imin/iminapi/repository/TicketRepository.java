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
     * Every SOLD ticket for an event joined to its order, for the attendee CSV
     * export. Tuple shape: {@code [Ticket ticket, String buyerEmail, String orderToken, Instant purchasedAt]}.
     * Ordered oldest order first.
     */
    @Query("""
            select t, o.email, o.token, o.createdAt
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
     * happens in Java. Postgres and H2 (PG-compat, used by the test suite) diverge on date
     * truncation, and this repo has been bitten by exactly that class of H2-passes /
     * PG-500s bug before. Row volume is bounded by one event's window (~10 days).
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

    long countByOrderIdAndStateNot(UUID orderId, String state);

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
}
