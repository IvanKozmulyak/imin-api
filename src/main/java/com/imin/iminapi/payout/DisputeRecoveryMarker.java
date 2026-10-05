package com.imin.iminapi.payout;

import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Commits the dispute recovery and return markers in their OWN transaction right after the Stripe call,
 * like {@link RefundRecoveryMarker}: the payout transaction around it may roll back after money moved.
 */
@Service
public class DisputeRecoveryMarker {

    private static final Logger log = LoggerFactory.getLogger(DisputeRecoveryMarker.class);

    private final DisputeRepository disputes;

    public DisputeRecoveryMarker(DisputeRepository disputes) {
        this.disputes = disputes;
    }

    /**
     * Add a reversal sized at {@code before}; {@code complete} closes the debt, otherwise the transfer capped it
     * and the rest stays owed ({@code amountMinor} 0 and no {@code trr_} close a debt with nothing left to take).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRecovered(UUID disputeId, long before, long amountMinor, String reversalId, boolean complete) {
        int n;
        if (!complete) {
            n = disputes.markPartlyRecovered(disputeId, before, amountMinor, reversalId, Times.nowMicros());
        } else if (amountMinor == 0L && reversalId == null) {
            n = disputes.closeRecovery(disputeId, before, Times.nowMicros());
        } else {
            n = disputes.markRecovered(disputeId, before, amountMinor, reversalId, Times.nowMicros());
        }
        if (n == 1) return;
        // Another run's marker is the claim; only a vanished row is worth a page.
        if (disputes.existsById(disputeId)) return;
        log.error("[payout] dispute {} vanished before its recovery marker could be written "
                + "(reversal {}, {}) — the money moved with nothing recording it", disputeId, reversalId, amountMinor);
    }

    /** Stamp the transfer that gave a recovered share back, and commit immediately. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReturned(UUID disputeId, String transferId, long before, long amountMinor) {
        if (disputes.markReturned(disputeId, transferId, before, amountMinor, Times.nowMicros()) == 1) return;
        if (disputes.existsById(disputeId)) return;
        log.error("[payout] dispute {} vanished before its return marker could be written "
                + "(transfer {}) — the money moved with nothing recording it", disputeId, transferId);
    }
}
