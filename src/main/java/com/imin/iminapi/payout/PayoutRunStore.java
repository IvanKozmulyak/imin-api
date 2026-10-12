package com.imin.iminapi.payout;

import com.imin.iminapi.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every {@code payout_runs} write around a Stripe payout create, each committed on its own. The PLANNED row
 * commits before the call, so a crash after Stripe accepted the payout leaves a row the resolver can settle
 * by listing, and every outcome commits right after the call, so no caller rollback can undo it.
 */
@Service
public class PayoutRunStore {

    private static final Logger log = LoggerFactory.getLogger(PayoutRunStore.class);

    /** The only states an outcome may overwrite: a create whose answer has not been recorded yet. */
    private static final List<PayoutRunStatus> UNRESOLVED = List.of(PayoutRunStatus.PLANNED, PayoutRunStatus.RETRYING);

    private final PayoutRunRepository payoutRuns;

    public PayoutRunStore(PayoutRunRepository payoutRuns) {
        this.payoutRuns = payoutRuns;
    }

    /** Commits the PLANNED row; a failed insert (incl. a UNIQUE(idempotency_key) clash) throws before Stripe. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PayoutRun plan(PayoutRun draft) {
        return payoutRuns.saveAndFlush(draft);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordSubmitted(UUID runId, String stripePayoutId, Instant submittedAt) {
        return applied(runId, PayoutRunStatus.SUBMITTED, payoutRuns.markSubmitted(runId, UNRESOLVED,
                PayoutRunStatus.SUBMITTED, stripePayoutId, submittedAt, Times.nowMicros()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordRetrying(UUID runId, String reason) {
        return outcome(runId, PayoutRunStatus.RETRYING, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailed(UUID runId, String reason) {
        return outcome(runId, PayoutRunStatus.FAILED, reason);
    }

    /** BLOCKED for a human: step 0a keeps the event out until someone settles it at Stripe. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean holdForHuman(UUID runId, String reason) {
        return outcome(runId, PayoutRunStatus.BLOCKED, reason);
    }

    private boolean outcome(UUID runId, PayoutRunStatus to, String reason) {
        return applied(runId, to, payoutRuns.markOutcome(runId, UNRESOLVED, to, reason, Times.nowMicros()));
    }

    private static boolean applied(UUID runId, PayoutRunStatus to, int rows) {
        if (rows == 1) return true;
        log.warn("[payout] run {} was no longer PLANNED/RETRYING — {} not recorded", runId, to);
        return false;
    }
}
