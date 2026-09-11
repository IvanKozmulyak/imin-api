package com.imin.iminapi.dispute;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    Optional<Dispute> findByStripeDisputeId(String stripeDisputeId);

    long countByOrgIdAndStatus(UUID orgId, DisputeStatus status);

    long countByOrderIdAndStatusAndIdNot(UUID orderId, DisputeStatus status, UUID excludedId);

    @Query("""
            select coalesce(sum(d.amountMinor), 0) from Dispute d
             where d.eventId = :eventId
               and d.status in :statuses
            """)
    long sumMinorByEventIdAndStatusIn(@Param("eventId") UUID eventId,
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
}
