package com.imin.iminapi.cancellation;

import com.imin.iminapi.cancellation.EventCancellationRepository.EventCount;
import com.imin.iminapi.stripe.StripeProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Carries out every cancellation: enrolls orders paid after the cancel, emails buyers, refunds up to {@link #BATCH}
 * orders (each only once its notice was attempted), and alerts on refunds not started 3 days after the cancel (refunds.md §4 allows 5 working days).
 */
@Component
public class EventCancellationSweeper {

    private static final Logger log = LoggerFactory.getLogger(EventCancellationSweeper.class);

    static final int BATCH = 50;
    static final int NOTIFY_BATCH = 100;
    /** No new order starts after this, so the tick ends inside lockAtMostFor (10 min). */
    static final Duration BUDGET = Duration.ofMinutes(4);
    private static final String OVERDUE_STATES = "('pending','in_progress','needs_attention')";

    private final EventCancellationRepository items;
    private final EventCancellationRefunder refunder;
    private final EventCancellationEmailer emailer;
    private final StripeProperties stripeProps;
    private final Clock clock;

    public EventCancellationSweeper(EventCancellationRepository items, EventCancellationRefunder refunder,
                                    EventCancellationEmailer emailer, StripeProperties stripeProps, Clock clock) {
        this.items = items;
        this.refunder = refunder;
        this.emailer = emailer;
        this.stripeProps = stripeProps;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    @SchedulerLock(name = "event_cancellation_refunds", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void tick() {
        long deadline = System.nanoTime() + BUDGET.toNanos();
        // Each step is isolated, so one failing query never skips the others.
        try {
            int enrolled = items.enrollAll(now());
            if (enrolled > 0) log.info("EventCancellationSweeper: enrolled {} order(s) paid after their cancel", enrolled);
        } catch (RuntimeException e) {
            log.error("EventCancellationSweeper: enrollment failed", e);
        }
        // Notices first: a refund can succeed at once and its confirmation must not reach the buyer before the notice.
        try {
            emailer.notifyNext(NOTIFY_BATCH);
        } catch (RuntimeException e) {
            log.error("EventCancellationSweeper: notice pass failed", e);
        }
        try {
            refund(deadline);
        } catch (RuntimeException e) {
            log.error("EventCancellationSweeper: refund pass failed", e);
        }
        try {
            alert();
        } catch (RuntimeException e) {
            log.error("EventCancellationSweeper: overdue check failed", e);
        }
    }

    private void refund(long deadline) {
        List<UUID> due = items.findDue(now(), BATCH);
        int done = 0, failed = 0, deferred = 0;
        for (UUID orderId : due) {
            if (System.nanoTime() > deadline) {
                deferred++;
                continue;
            }
            try {
                refunder.process(orderId);
                done++;
            } catch (RuntimeException e) {
                failed++;
                log.error("EventCancellationSweeper: order {} failed", orderId, e);
            }
        }
        if (!due.isEmpty()) {
            log.info("EventCancellationSweeper: planned={} processed={} failed={} deferred={}",
                    due.size(), done, failed, deferred);
        }
    }

    private void alert() {
        Instant cutoff = now().minus(EventCancellationService.OVERDUE_AFTER);
        boolean testMode = !stripeProps.isLiveKey();
        for (EventCount c : items.countByEventCancelledBefore(OVERDUE_STATES, cutoff, testMode)) {
            log.error("[CANCELLATION_REFUND_SLA] eventId={} overdue={}", c.eventId(), c.count());
        }
        for (EventCount c : items.countByEventCancelledBefore("('waiting_dispute')", cutoff, testMode)) {
            log.warn("[cancellation] eventId={} {} order(s) still wait on a payment dispute", c.eventId(), c.count());
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
