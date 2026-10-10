package com.imin.iminapi.refund;

import com.imin.iminapi.refund.event.RefundFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every write of a refund attempt, each in its own committed transaction. The attempt row and its
 * ticket claims are durable before Stripe is called, and Stripe's answer is recorded right after,
 * so no caller rollback can erase a refund Stripe already made.
 */
@Component
public class RefundAttemptStore {

    private static final Logger log = LoggerFactory.getLogger(RefundAttemptStore.class);
    static final int FAILURE_CODE_MAX = 64;
    static final int FAILURE_MESSAGE_MAX = 500;

    private final RefundRepository refunds;
    private final RefundTicketRepository refundTickets;
    private final RefundInventoryRelease inventoryRelease;
    private final ApplicationEventPublisher publisher;

    public RefundAttemptStore(RefundRepository refunds, RefundTicketRepository refundTickets,
                              RefundInventoryRelease inventoryRelease, ApplicationEventPublisher publisher) {
        this.refunds = refunds;
        this.refundTickets = refundTickets;
        this.inventoryRelease = inventoryRelease;
        this.publisher = publisher;
    }

    /**
     * Commits the REQUESTED row and claims its tickets against UNIQUE(refund_tickets.ticket_id).
     * A lost race surfaces as the {@link org.springframework.dao.DataIntegrityViolationException}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Refund open(Refund draft, List<UUID> ticketIds, Instant now) {
        draft.setStatus(RefundStatus.REQUESTED);
        draft.setStripeAttemptAt(now);
        draft.setStripeAttempts(1);
        Refund row = refunds.saveAndFlush(draft);
        List<RefundTicket> claims = new ArrayList<>(ticketIds.size());
        for (UUID ticketId : ticketIds) claims.add(new RefundTicket(row.getId(), ticketId));
        // saveAllAndFlush: RefundTicket has an assigned @IdClass id, so a plain save only queues the INSERT.
        refundTickets.saveAllAndFlush(claims);
        return row;
    }

    /**
     * Records the Refund Stripe returned. Side effects run only when this write moved the row out of
     * REQUESTED, so a webhook that got there first is never doubled; a failed/canceled refund object
     * releases its claims and frees the client key. Returns the row as it now is.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Refund recordOutcome(UUID refundId, com.stripe.model.Refund stripeRefund) {
        RefundStatus status = RefundStatus.fromStripe(stripeRefund.getStatus());
        boolean failed = status == RefundStatus.FAILED || status == RefundStatus.CANCELED;
        String reason = failed ? stripeRefund.getFailureReason() : null;
        int won = refunds.recordOutcome(refundId, stripeRefund.getId(), stripeRefund.getCharge(), status,
            truncate(reason, FAILURE_CODE_MAX), truncate(reason, FAILURE_MESSAGE_MAX));
        if (won == 1) {
            if (status == RefundStatus.SUCCEEDED) {
                inventoryRelease.release(refundId);
            } else if (failed) {
                // No money moved: the tickets must be refundable again.
                long released = refundTickets.deleteByRefundId(refundId);
                refunds.freeClientKey(refundId, failedKey(refundId));
                if (status == RefundStatus.FAILED) publisher.publishEvent(new RefundFailedEvent(refundId));
                log.warn("[refund] refund {} {} at Stripe ({}) — released {} ticket claim(s)",
                    refundId, status, reason, released);
            }
        }
        Refund row = refunds.findById(refundId).orElseThrow();
        if (won == 0 && row.getStripeRefundId() != null && !row.getStripeRefundId().equals(stripeRefund.getId())) {
            log.error("[REFUND_SECOND_STRIPE_REFUND] refundId={} existing={} new={}",
                refundId, row.getStripeRefundId(), stripeRefund.getId());
        }
        return row;
    }

    /** Stripe refused and holds no refund: FAILED, claims released, client key freed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordRefusal(UUID refundId, String failureCode, String failureMessage) {
        int won = refunds.recordRefusal(refundId, truncate(failureCode, FAILURE_CODE_MAX),
            truncate(failureMessage, FAILURE_MESSAGE_MAX), failedKey(refundId));
        if (won == 0) return false;
        long released = refundTickets.deleteByRefundId(refundId);
        log.warn("[refund] refund {} refused by Stripe code={} — released {} ticket claim(s)",
            refundId, failureCode, released);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean switchToPlatform(UUID refundId, Instant now) {
        return refunds.switchToPlatform(refundId, now) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimForReconcile(UUID refundId, Instant seenAttemptAt, Instant cutoff, Instant now) {
        return refunds.claimForReconcile(refundId, seenAttemptAt, cutoff, now) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimPendingCheck(UUID refundId, Instant cutoff, Instant now) {
        return refunds.claimPendingCheck(refundId, cutoff, now) == 1;
    }

    static String failedKey(UUID refundId) {
        return "failed:" + refundId;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
