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
               and d.status in :statuses
            """)
    long countDistinctOrderIdByEventIdAndStatusIn(@Param("eventId") UUID eventId,
                                                  @Param("statuses") Collection<DisputeStatus> statuses);

    long countByOrgIdAndStatus(UUID orgId, DisputeStatus status);

    long countByOrderIdAndStatusInAndIdNot(UUID orderId, Collection<DisputeStatus> statuses,
                                           UUID excludedId);

    @Query("""
            select coalesce(sum(d.amountMinor), 0) from Dispute d
             where d.eventId = :eventId
               and d.status in :statuses
            """)
    long sumMinorByEventIdAndStatusIn(@Param("eventId") UUID eventId,
                                      @Param("statuses") Collection<DisputeStatus> statuses);

    /** Same sum, LIVE-mode rows only (V130) — the payout path's variant. */
    @Query("""
            select coalesce(sum(d.amountMinor), 0) from Dispute d
             where d.eventId = :eventId
               and d.status in :statuses
               and d.testMode = false
            """)
    long sumLiveMinorByEventIdAndStatusIn(@Param("eventId") UUID eventId,
                                          @Param("statuses") Collection<DisputeStatus> statuses);

    /**
     * The org-level payout gate: any OPEN dispute freezes every payout for the org, because
     * the connected balance is one shared pool and the funds may still be clawed back. A
     * CLOSED dispute — won or lost — never blocks; a loss is settled by the net reduction
     * below instead of by an indefinite freeze.
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
     * Face value this event must not pay out: disputes still OPEN plus those definitively
     * LOST. WON and WITHDRAWN_REINSTATED are excluded, which is how a reinstatement adds the
     * money back — the sum simply stops counting it.
     */
    default long sumOpenOrLostMinorByEventId(UUID eventId) {
        return sumMinorByEventIdAndStatusIn(eventId, DisputeWithholding.STATUSES);
    }

    /**
     * The payout path's variant of {@link #sumOpenOrLostMinorByEventId}: LIVE-mode disputes
     * only (V130). A test-era chargeback clawed back no real money, so subtracting it from a
     * live net would withhold the organizer's own funds against a loss that never happened.
     */
    default long sumOpenOrLostLiveMinorByEventId(UUID eventId) {
        return sumLiveMinorByEventIdAndStatusIn(eventId, DisputeWithholding.STATUSES);
    }

    /**
     * Orders on this event whose money is being withheld — one per order however many
     * chargebacks it collected, because the organizer-facing counter counts orders.
     */
    default long countOpenOrLostOrdersByEventId(UUID eventId) {
        return countDistinctOrderIdByEventIdAndStatusIn(eventId, DisputeWithholding.STATUSES);
    }

    /** Is this order's money at risk or already gone? The refund gate's question. */
    default boolean hasOpenOrLostByOrderId(UUID orderId) {
        return countByOrderIdAndStatusIn(orderId, DisputeWithholding.STATUSES) > 0;
    }
}
