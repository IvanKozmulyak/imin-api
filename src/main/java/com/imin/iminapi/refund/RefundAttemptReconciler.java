package com.imin.iminapi.refund;

import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.stripe.StripeRefundService;
import com.stripe.exception.StripeException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Settles refunds whose Stripe state imin does not know, in two capped passes, only on orders in the
 * running key's mode: attempts with an unknown outcome (409 {@code REFUND_IN_PROGRESS}, or a process that
 * died mid-call) adopt the Stripe refund carrying their metadata or are re-sent with their own key;
 * PENDING refunds whose webhook never came are re-read from Stripe and run through the webhook's own
 * transition. REQUESTED rows with no attempt time are only reported: none can arise since V179.
 */
@Component
public class RefundAttemptReconciler {

    private static final Logger log = LoggerFactory.getLogger(RefundAttemptReconciler.class);

    static final Duration MIN_AGE = RefundService.RECONCILE_MIN_AGE;
    /** Well past webhook latency; a refund still pending at Stripe is re-read at most hourly. */
    static final Duration PENDING_RECHECK_AFTER = Duration.ofHours(1);
    static final int BATCH = 25;
    /**
     * No new row starts after this; with one row's worst common case (a one-page list plus two creates at
     * the 110 s call ceiling, about 5.5 min) the tick stays inside lockAtMostFor. Unreached rows wait a tick.
     */
    static final Duration BUDGET = Duration.ofMinutes(4);
    static final int ERROR_FROM_ATTEMPT = 12;

    private final RefundRepository refunds;
    private final RefundService refundService;
    private final RefundAttemptStore store;
    private final StripeRefundService stripeRefundService;
    private final StripeProperties stripeProps;
    private final Clock clock;

    public RefundAttemptReconciler(RefundRepository refunds, RefundService refundService, RefundAttemptStore store,
                                   StripeRefundService stripeRefundService, StripeProperties stripeProps,
                                   Clock clock) {
        this.refunds = refunds;
        this.refundService = refundService;
        this.store = store;
        this.stripeRefundService = stripeRefundService;
        this.stripeProps = stripeProps;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    @SchedulerLock(name = "refund_attempt_reconcile", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void reconcile() {
        boolean testMode = !stripeProps.isLiveKey();
        long deadline = System.nanoTime() + BUDGET.toNanos();
        // Each pass is isolated, so one failing query never skips the others.
        try {
            reconcileUnresolvedAttempts(testMode, deadline);
        } catch (RuntimeException e) {
            log.error("RefundAttemptReconciler: unresolved-attempt pass failed", e);
        }
        try {
            recheckStalePending(testMode, deadline);
        } catch (RuntimeException e) {
            log.error("RefundAttemptReconciler: pending pass failed", e);
        }
        try {
            long unattempted = refunds.countUnattemptedRequested();
            if (unattempted > 0) {
                log.error("[REFUND_UNATTEMPTED] {} REQUESTED refund(s) with no Stripe id and no attempt time; "
                    + "not reconciled, resolve by hand", unattempted);
            }
        } catch (RuntimeException e) {
            log.error("RefundAttemptReconciler: unattempted count failed", e);
        }
    }

    private void reconcileUnresolvedAttempts(boolean testMode, long deadline) {
        List<Refund> due = refunds.findUnresolvedAttempts(clock.instant().minus(MIN_AGE), testMode,
            PageRequest.of(0, BATCH));
        int adopted = 0, resent = 0, refused = 0, unresolved = 0, skipped = 0, failed = 0, deferred = 0;
        for (Refund row : due) {
            if (System.nanoTime() > deadline) {
                deferred++;
                continue;
            }
            try {
                RefundService.Resolution r = refundService.resolveAttempt(row.getId());
                switch (r) {
                    case ADOPTED -> adopted++;
                    case RESENT -> resent++;
                    case REFUSED -> refused++;
                    case UNRESOLVED -> unresolved++;
                    case SKIPPED -> skipped++;
                }
                if (r == RefundService.Resolution.UNRESOLVED && row.getStripeAttempts() + 1 >= ERROR_FROM_ATTEMPT) {
                    log.error("[REFUND_UNRESOLVED] refundId={} attempts={}", row.getId(), row.getStripeAttempts() + 1);
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("RefundAttemptReconciler: refund {} failed", row.getId(), e);
            }
        }
        summary("RefundAttemptReconciler: planned={} adopted={} resent={} refused={} unresolved={} "
            + "skipped={} failed={} deferred={}", due.size(), unresolved + failed,
            due.size(), adopted, resent, refused, unresolved, skipped, failed, deferred);
    }

    /** PENDING refunds whose webhook never came: Stripe's record, through the webhook's own transition. */
    private void recheckStalePending(boolean testMode, long deadline) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(PENDING_RECHECK_AFTER);
        List<Refund> due = refunds.findStalePending(cutoff, testMode, PageRequest.of(0, BATCH));
        int moved = 0, unchanged = 0, skipped = 0, unreadable = 0, failed = 0, deferred = 0;
        for (Refund row : due) {
            if (System.nanoTime() > deadline) {
                deferred++;
                continue;
            }
            try {
                if (!store.claimPendingCheck(row.getId(), cutoff, now)) {
                    skipped++;
                    continue;
                }
                com.stripe.model.Refund onStripe;
                try {
                    onStripe = stripeRefundService.retrieve(row.getStripeRefundId());
                } catch (StripeException | RuntimeException e) {
                    unreadable++;
                    log.warn("[refund] pending recheck could not read refundId={} stripeRefundId={}",
                        row.getId(), row.getStripeRefundId(), e);
                    continue;
                }
                refundService.applyStripeRefund(onStripe);
                boolean stillPending = refunds.findById(row.getId())
                    .map(r -> r.getStatus() == RefundStatus.PENDING).orElse(false);
                if (stillPending) unchanged++;
                else moved++;
            } catch (RuntimeException e) {
                failed++;
                log.error("RefundAttemptReconciler: pending refund {} failed", row.getId(), e);
            }
        }
        summary("RefundAttemptReconciler: pending planned={} moved={} unchanged={} skipped={} unreadable={} "
            + "failed={} deferred={}", due.size(), unreadable + failed,
            due.size(), moved, unchanged, skipped, unreadable, failed, deferred);
    }

    /** DEBUG when nothing was planned, ERROR when every planned row went bad, WARN when some did. */
    private static void summary(String format, int planned, int bad, Object... args) {
        if (planned == 0) log.debug(format, args);
        else if (bad == planned) log.error(format, args);
        else if (bad > 0) log.warn(format, args);
        else log.info(format, args);
    }
}
