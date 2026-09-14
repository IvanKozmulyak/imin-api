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

    long countByOrgIdAndStatus(UUID orgId, DisputeStatus status);

    long countByOrderIdAndStatusAndIdNot(UUID orderId, DisputeStatus status, UUID excludedId);

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
     * OPEN disputes on the same order other than {@code excludedId}. A win only restores the
     * order's tickets when this is zero — the tickets are per-order, so un-revoking them while a
     * second chargeback on the same order is still live would hand back working entry.
     */
    default long countOtherOpenByOrderId(UUID orderId, UUID excludedId) {
        return countByOrderIdAndStatusAndIdNot(orderId, DisputeStatus.OPEN, excludedId);
    }

    /**
     * Face value this event must not pay out: disputes still OPEN plus those definitively
     * LOST. WON and WITHDRAWN_REINSTATED are excluded, which is how a reinstatement adds the
     * money back — the sum simply stops counting it.
     */
    default long sumOpenOrLostMinorByEventId(UUID eventId) {
        return sumMinorByEventIdAndStatusIn(eventId, List.of(DisputeStatus.OPEN, DisputeStatus.LOST));
    }

    /**
     * The payout path's variant of {@link #sumOpenOrLostMinorByEventId}: LIVE-mode disputes
     * only (V130). A test-era chargeback clawed back no real money, so subtracting it from a
     * live net would withhold the organizer's own funds against a loss that never happened.
     */
    default long sumOpenOrLostLiveMinorByEventId(UUID eventId) {
        return sumLiveMinorByEventIdAndStatusIn(eventId, List.of(DisputeStatus.OPEN, DisputeStatus.LOST));
    }
}
