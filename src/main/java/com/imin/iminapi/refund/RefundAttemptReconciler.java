package com.imin.iminapi.refund;

import com.imin.iminapi.stripe.StripeProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Resolves refund attempts whose Stripe outcome is unknown (409 {@code REFUND_IN_PROGRESS}, or a
 * process that died mid-call): adopts the Stripe refund carrying the row's metadata, or re-sends the
 * stored request with its own key when Stripe has none. Only rows in the running key's mode.
 */
@Component
public class RefundAttemptReconciler {

    private static final Logger log = LoggerFactory.getLogger(RefundAttemptReconciler.class);

    static final Duration MIN_AGE = RefundService.RECONCILE_MIN_AGE;
    static final int BATCH = 25;
    static final int ERROR_FROM_ATTEMPT = 12;

    private final RefundRepository refunds;
    private final RefundService refundService;
    private final StripeProperties stripeProps;
    private final Clock clock;

    public RefundAttemptReconciler(RefundRepository refunds, RefundService refundService,
                                   StripeProperties stripeProps, Clock clock) {
        this.refunds = refunds;
        this.refundService = refundService;
        this.stripeProps = stripeProps;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    @SchedulerLock(name = "refund_attempt_reconcile", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void reconcile() {
        List<Refund> due = refunds.findUnresolvedAttempts(clock.instant().minus(MIN_AGE),
            !stripeProps.isLiveKey(), PageRequest.of(0, BATCH));
        int adopted = 0, resent = 0, refused = 0, unresolved = 0, skipped = 0, failed = 0;
        for (Refund row : due) {
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
        String summary = "RefundAttemptReconciler: planned={} adopted={} resent={} refused={} unresolved={} "
            + "skipped={} failed={}";
        Object[] args = {due.size(), adopted, resent, refused, unresolved, skipped, failed};
        int bad = unresolved + failed;
        if (due.isEmpty()) log.debug(summary, args);
        else if (bad == due.size()) log.error(summary, args);
        else if (bad > 0) log.warn(summary, args);
        else log.info(summary, args);
    }
}
