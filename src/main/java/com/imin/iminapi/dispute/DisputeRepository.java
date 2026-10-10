package com.imin.iminapi.dispute;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    Optional<Dispute> findByStripeDisputeId(String stripeDisputeId);

    /** Disputes for this PaymentIntent that never found their order — the race's leftovers. */
    List<Dispute> findByStripePaymentIntentIdAndOrderIdIsNull(String stripePaymentIntentId);

    /**
     * Unattributed disputes recent enough to be worth re-checking. Bounded by the caller's
     * {@link Pageable}: the sweep is a safety net, not a backfill.
     */
    List<Dispute> findByOrderIdIsNullAndStripePaymentIntentIdIsNotNullAndCreatedAtAfter(
            Instant createdAfter, Pageable pageable);

    /**
     * Attributed withholding disputes whose order still has a live ticket — the rows the sweep's
     * second pass would actually change. The predicate mirrors the revoke skip rule, so once a
     * row has converged it is simply not found again and the pass writes nothing.
     *
     * <p>{@code not in ('refunded','revoked')} rather than an enumerating list: legacy tickets
     * carry {@code pre} as a synonym for {@code issued} and would otherwise stay scannable.
     */
    @Query("""
            select d from Dispute d
             where d.orderId is not null and d.status in :statuses and d.createdAt > :createdAfter
               and exists (select 1 from com.imin.iminapi.model.Ticket t
                            where t.orderId = d.orderId and t.state not in ('refunded', 'revoked'))
            """)
    List<Dispute> findAttributedWithLiveTickets(@Param("statuses") Collection<DisputeStatus> statuses,
                                                @Param("createdAfter") Instant createdAfter,
                                                Pageable pageable);

    /**
     * Attach an orphan to the order that turned up later. {@code and d.orderId is null} is the
     * race guard: whichever of the checkout call site and the sweep gets there first wins, and
     * the loser updates 0 rows instead of overwriting an attribution.
     *
     * <p>{@code updatedAt} is passed in because {@code @PreUpdate} does not fire on a bulk
     * update, and the caller must NOT {@code save} the entity afterwards — this write bypasses
     * the persistence context, so the in-memory row is stale the moment it returns.
     *
     * <p>{@code clearAutomatically = false} on purpose: this runs inside paid fulfilment, and
     * clearing would detach the order, tickets, tier and reservation that transaction still owns.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("""
            update Dispute d
               set d.orderId = :orderId, d.eventId = :eventId, d.orgId = :orgId,
                   d.testMode = :testMode, d.updatedAt = :now
             where d.id = :id and d.orderId is null
            """)
    int attachToOrder(@Param("id") UUID id,
                      @Param("orderId") UUID orderId,
                      @Param("eventId") UUID eventId,
                      @Param("orgId") UUID orgId,
                      @Param("testMode") boolean testMode,
                      @Param("now") Instant now);

    /**
     * Every dispute attached to any of these orders, for a listing that renders one row per
     * order. One query for the page — the caller groups by {@code orderId} itself.
     */
    List<Dispute> findByOrderIdIn(Collection<UUID> orderIds);

    long countByOrderIdAndStatusIn(UUID orderId, Collection<DisputeStatus> statuses);

    @Query("""
            select count(distinct d.orderId) from Dispute d
             where d.eventId = :eventId
               and d.orderId is not null
               and d.testMode = false
               and d.status in :statuses
            """)
    long countDistinctOrderIdByEventIdAndStatusIn(@Param("eventId") UUID eventId,
                                                  @Param("statuses") Collection<DisputeStatus> statuses);

    long countByOrgIdAndStatus(UUID orgId, DisputeStatus status);

    long countByOrderIdAndStatusInAndIdNot(UUID orderId, Collection<DisputeStatus> statuses,
                                           UUID excludedId);

    /**
     * Withholding LIVE-mode disputes summed per order, for every event in {@code eventIds}: the
     * organizer readouts. Left join: a dispute with no order keeps a row with null total and fee.
     */
    @Query("""
            select new com.imin.iminapi.dispute.DisputeOrderRow(
                       d.eventId, d.orderId, o.totalMinor, o.applicationFeeMinor, sum(d.amountMinor))
              from Dispute d left join com.imin.iminapi.model.Order o on o.id = d.orderId
             where d.eventId in :eventIds
               and d.testMode = false
               and d.status in :statuses
             group by d.eventId, d.orderId, o.totalMinor, o.applicationFeeMinor
            """)
    List<DisputeOrderRow> withholdingRowsByEventIds(@Param("eventIds") Collection<UUID> eventIds,
                                                    @Param("statuses") Collection<DisputeStatus> statuses);

    /**
     * {@link #withholdingRowsByEventIds} for one event, LIVE-mode disputes only (V130), with what Stripe
     * settled for each order: the payout path's variant, since a test-era chargeback clawed back no real money.
     */
    @Query("""
            select new com.imin.iminapi.dispute.DisputeSettlementRow(
                       d.eventId, d.orderId, o.totalMinor, o.applicationFeeMinor,
                       o.settlementCurrency, o.settlementGrossMinor, o.settlementFeeMinor, sum(d.amountMinor))
              from Dispute d left join com.imin.iminapi.model.Order o on o.id = d.orderId
             where d.eventId = :eventId
               and d.testMode = false
               and d.status in :statuses
             group by d.eventId, d.orderId, o.totalMinor, o.applicationFeeMinor,
                      o.settlementCurrency, o.settlementGrossMinor, o.settlementFeeMinor
            """)
    List<DisputeSettlementRow> liveWithholdingRowsByEventId(@Param("eventId") UUID eventId,
                                                       @Param("statuses") Collection<DisputeStatus> statuses);

    /**
     * Withholding LIVE-mode disputes summed per order, on the org's orders created in
     * {@code [since, until)}. A dispute with no order cannot be placed in a window and is not counted.
     */
    @Query("""
            select new com.imin.iminapi.dispute.DisputeOrderRow(
                       d.eventId, d.orderId, o.totalMinor, o.applicationFeeMinor, sum(d.amountMinor))
              from Dispute d join com.imin.iminapi.model.Order o on o.id = d.orderId
             where d.orgId = :orgId
               and o.orgId = :orgId
               and o.createdAt >= :since
               and o.createdAt < :until
               and d.testMode = false
               and d.status in :statuses
             group by d.eventId, d.orderId, o.totalMinor, o.applicationFeeMinor
            """)
    List<DisputeOrderRow> withholdingRowsByOrgOrderWindow(@Param("orgId") UUID orgId,
                                                          @Param("since") Instant since,
                                                          @Param("until") Instant until,
                                                          @Param("statuses") Collection<DisputeStatus> statuses);

    /** The org's LOST disputes whose organizer share is not reversed yet, in one Stripe mode, oldest first. */
    @Query("""
            select d from Dispute d
             where d.orgId = :orgId
               and d.status = :lost
               and d.recoveredAt is null
               and d.orderId is not null
               and d.testMode = :testMode
             order by d.createdAt
            """)
    List<Dispute> findUnrecoveredLostByOrgId(@Param("orgId") UUID orgId,
                                             @Param("lost") DisputeStatus lost,
                                             @Param("testMode") boolean testMode);

    /** Rows still holding reversed money on orders where a dispute turned won or reinstated, oldest first. */
    @Query("""
            select d from Dispute d
             where d.orgId = :orgId
               and d.recoveredAt is not null
               and d.recoveredMinor - coalesce(d.returnedMinor, 0) > 0
               and d.testMode = :testMode
               and exists (select 1 from Dispute w where w.orderId = d.orderId and w.status in :back)
             order by d.createdAt
            """)
    List<Dispute> findReturnCandidatesByOrgId(@Param("orgId") UUID orgId,
                                              @Param("back") Collection<DisputeStatus> back,
                                              @Param("testMode") boolean testMode);

    /** Every org with a LOST dispute still to reverse or a recovered one still to transfer back. */
    @Query("""
            select distinct d.orgId from Dispute d
             where d.testMode = :testMode
               and ((d.status = :lost and d.recoveredAt is null and d.orderId is not null)
                 or (d.recoveredAt is not null and d.recoveredMinor - coalesce(d.returnedMinor, 0) > 0
                     and exists (select 1 from Dispute w where w.orderId = d.orderId and w.status in :back)))
            """)
    List<UUID> findOrgIdsOwingDisputeMoney(@Param("lost") DisputeStatus lost,
                                           @Param("back") Collection<DisputeStatus> back,
                                           @Param("testMode") boolean testMode);

    @Query("""
            select coalesce(sum(d.amountMinor), 0) from Dispute d
             where d.orderId = :orderId and d.status = :lost
            """)
    long sumLostAmountByOrderId(@Param("orderId") UUID orderId, @Param("lost") DisputeStatus lost);

    /** Other disputes on the order that still block a return: OPEN, or LOST and not reversed yet. */
    @Query("""
            select count(d) from Dispute d
             where d.orderId = :orderId and d.id <> :id
               and (d.status = :open or (d.status = :lost and d.recoveredAt is null))
            """)
    long countOtherOpenOrUnrecoveredLostByOrderId(@Param("orderId") UUID orderId, @Param("id") UUID id,
                                                  @Param("open") DisputeStatus open,
                                                  @Param("lost") DisputeStatus lost);

    /** The row's returned sum read from the database, never from a stale persistence context. */
    @Query("select coalesce(d.returnedMinor, 0) from Dispute d where d.id = :id")
    long returnedMinorById(@Param("id") UUID id);

    /** What this order's reversals still hold, capped ones included: Σ (recovered − returned). */
    @Query("""
            select coalesce(sum(coalesce(d.recoveredMinor, 0) - coalesce(d.returnedMinor, 0)), 0) from Dispute d
             where d.orderId = :orderId
            """)
    long sumHeldByOrderId(@Param("orderId") UUID orderId);

    /**
     * Add a reversal and close the debt. Lands only from the {@code before} it was sized at and while the
     * debt is open; with {@link #markPartlyRecovered} the only writer of these {@code updatable = false} columns.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Dispute d
               set d.recoveredAt = :now, d.recoveredMinor = coalesce(d.recoveredMinor, 0) + :amount,
                   d.recoveryReversalId = :reversalId, d.updatedAt = :now
             where d.id = :id and d.recoveredAt is null and coalesce(d.recoveredMinor, 0) = :before
            """)
    int markRecovered(@Param("id") UUID id,
                      @Param("before") long before,
                      @Param("amount") long amount,
                      @Param("reversalId") String reversalId,
                      @Param("now") Instant now);

    /** Close the debt with nothing more taken: an earlier capped reversal's id and amount stay as they are. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Dispute d
               set d.recoveredAt = :now, d.recoveredMinor = coalesce(d.recoveredMinor, 0), d.updatedAt = :now
             where d.id = :id and d.recoveredAt is null and coalesce(d.recoveredMinor, 0) = :before
            """)
    int closeRecovery(@Param("id") UUID id, @Param("before") long before, @Param("now") Instant now);

    /** Add a reversal the transfer capped short of the debt; {@code recoveredAt} stays null, so the rest stays owed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Dispute d
               set d.recoveredMinor = coalesce(d.recoveredMinor, 0) + :amount,
                   d.recoveryReversalId = :reversalId, d.updatedAt = :now
             where d.id = :id and d.recoveredAt is null and coalesce(d.recoveredMinor, 0) = :before
            """)
    int markPartlyRecovered(@Param("id") UUID id,
                            @Param("before") long before,
                            @Param("amount") long amount,
                            @Param("reversalId") String reversalId,
                            @Param("now") Instant now);

    /** The row's reversed sum read from the database, never from a stale persistence context. */
    @Query("select coalesce(d.recoveredMinor, 0) from Dispute d where d.id = :id")
    long recoveredMinorById(@Param("id") UUID id);

    /**
     * Add one transfer back to {@code returnedMinor}. Lands only from the {@code before} it was sent at
     * (so one transfer counts once) and never past what was recovered.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Dispute d
               set d.returnedMinor = coalesce(d.returnedMinor, 0) + :amount, d.returnedAt = :now,
                   d.returnTransferId = :transferId, d.updatedAt = :now
             where d.id = :id and d.recoveredAt is not null
               and coalesce(d.returnedMinor, 0) = :before
               and coalesce(d.returnedMinor, 0) + :amount <= d.recoveredMinor
            """)
    int markReturned(@Param("id") UUID id,
                     @Param("transferId") String transferId,
                     @Param("before") long before,
                     @Param("amount") long amount,
                     @Param("now") Instant now);

    /**
     * Live unreversed LOST disputes per order that settled in {@code currency} (lowercase) on the org's other
     * events: a payout's hold. An unstamped order matches no currency; the payout refuses while one exists.
     */
    @Query("""
            select new com.imin.iminapi.dispute.DisputeSettlementRow(
                       d.eventId, d.orderId, o.totalMinor, o.applicationFeeMinor,
                       o.settlementCurrency, o.settlementGrossMinor, o.settlementFeeMinor, sum(d.amountMinor))
              from Dispute d join com.imin.iminapi.model.Order o on o.id = d.orderId
             where d.orgId = :orgId
               and d.status = :lost
               and d.testMode = false
               and d.recoveredAt is null
               and d.eventId <> :eventId
               and o.settlementCurrency = :currency
             group by d.eventId, d.orderId, o.totalMinor, o.applicationFeeMinor,
                      o.settlementCurrency, o.settlementGrossMinor, o.settlementFeeMinor
            """)
    List<DisputeSettlementRow> unrecoveredLostRowsByOrgExcludingEvent(@Param("orgId") UUID orgId,
                                                                 @Param("lost") DisputeStatus lost,
                                                                 @Param("eventId") UUID eventId,
                                                                 @Param("currency") String currency);

    /** Live unreversed LOST disputes of the org on orders Stripe has not been read for: a debt that cannot be sized. */
    @Query("""
            select count(d) from Dispute d join com.imin.iminapi.model.Order o on o.id = d.orderId
             where d.orgId = :orgId and d.status = :lost and d.testMode = false
               and d.recoveredAt is null and o.settlementCurrency is null
            """)
    long countLiveUnrecoveredLostOnUnstampedOrdersByOrgId(@Param("orgId") UUID orgId,
                                                          @Param("lost") DisputeStatus lost);

    /** {@link #countLiveUnrecoveredLostOnUnstampedOrdersByOrgId} for orders created before {@code before}: stuck. */
    @Query("""
            select count(d) from Dispute d join com.imin.iminapi.model.Order o on o.id = d.orderId
             where d.orgId = :orgId and d.status = :lost and d.testMode = false
               and d.recoveredAt is null and o.settlementCurrency is null and o.createdAt < :before
            """)
    long countLiveUnrecoveredLostOnUnstampedOrdersByOrgIdCreatedBefore(@Param("orgId") UUID orgId,
                                                                       @Param("lost") DisputeStatus lost,
                                                                       @Param("before") java.time.Instant before);

    /**
     * The org-level payout gate: any OPEN dispute freezes every payout for the org, because
     * the connected balance is one shared pool and the funds may still be clawed back. A
     * CLOSED dispute — won or lost — never blocks; a loss is settled by withholding the
     * organizer's share of the order from the event's net instead of by an indefinite freeze.
     */
    default long countOpenByOrgId(UUID orgId) {
        return countByOrgIdAndStatus(orgId, DisputeStatus.OPEN);
    }

    /**
     * Withholding disputes on the same order other than {@code excludedId}. A win only restores
     * the order's tickets when this is zero — the tickets are per-order, so un-revoking them
     * while a second chargeback on the same order still withholds would hand back working entry.
     *
     * <p>OPEN <em>or</em> LOST, the same set every other withholding readout uses: a LOST sibling
     * means that money is gone for good, and restoring over it would re-revoke on the next sweep.
     */
    default long countOtherOpenOrLostByOrderId(UUID orderId, UUID excludedId) {
        return countByOrderIdAndStatusInAndIdNot(orderId, DisputeWithholding.STATUSES, excludedId);
    }

    /**
     * Orders on this event whose money is being withheld by a LIVE-mode chargeback — one per order
     * however many it collected, because the organizer-facing counter counts orders.
     */
    default long countOpenOrLostOrdersByEventId(UUID eventId) {
        return countDistinctOrderIdByEventIdAndStatusIn(eventId, DisputeWithholding.STATUSES);
    }

    /** Is this order's money at risk or already gone? The refund gate's question. */
    default boolean hasOpenOrLostByOrderId(UUID orderId) {
        return countByOrderIdAndStatusIn(orderId, DisputeWithholding.STATUSES) > 0;
    }
}
