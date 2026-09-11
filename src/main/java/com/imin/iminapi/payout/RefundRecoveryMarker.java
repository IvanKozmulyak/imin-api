package com.imin.iminapi.payout;

import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Commits the "imin has pulled this platform-funded refund back" marker in a transaction of
 * its OWN, the moment the Stripe transfer reversal returns.
 *
 * <p>It is a separate bean because the caller ({@link PostEventPayoutService#payOneEvent})
 * runs inside a {@code REQUIRES_NEW} transaction that is EXPECTED to roll back — a UNIQUE
 * violation on the {@code payout_runs} insert is a normal outcome there. A marker written in
 * that transaction would roll back with it while the reversal had already moved real money,
 * and the next nightly tick (24h later, by which time Stripe may have dropped the idempotency
 * key) would reverse a second time. A self-invocation would not be proxied, so the
 * {@code REQUIRES_NEW} has to cross a bean boundary.
 */
@Service
public class RefundRecoveryMarker {

    private static final Logger log = LoggerFactory.getLogger(RefundRecoveryMarker.class);

    private final RefundRepository refunds;

    public RefundRecoveryMarker(RefundRepository refunds) {
        this.refunds = refunds;
    }

    /**
     * Stamp {@code recovered_at} / {@code recovery_reversal_id} and commit immediately.
     *
     * @param refundId   the platform-funded refund whose debt was just pulled back.
     * @param reversalId the {@code trr_} the reversal returned, or null when there was
     *                   nothing of the organizer's to reverse (fee-only refund).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRecovered(UUID refundId, String reversalId) {
        Refund refund = refunds.findById(refundId).orElse(null);
        if (refund == null) {
            log.error("[payout] refund {} vanished before its recovery marker could be written "
                    + "(reversal {}) — the money moved with nothing recording it", refundId, reversalId);
            return;
        }
        // Another replica may have won the same reversal; its marker is the claim, not ours.
        if (refund.getRecoveredAt() != null) return;
        refund.setRecoveredAt(Instant.now());
        refund.setRecoveryReversalId(reversalId);
        refunds.save(refund);
    }
}
