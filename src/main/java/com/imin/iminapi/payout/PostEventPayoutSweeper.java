package com.imin.iminapi.payout;

import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.stripe.StripeProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Track B (manual payouts) Phase 2 — the daily post-event payout job.
 *
 * <p>Once a day it asks "which events are now past {@code endsAt + bufferDays} and
 * still unpaid, for a payout-eligible org?" and pays each in its own
 * {@code REQUIRES_NEW} transaction via {@link PostEventPayoutService}. It does NOT
 * arm a per-event timer for "exactly +N days" — daily polling is the right cadence
 * because {@code payout_at} has day granularity, idempotency (the deterministic
 * {@code payout_runs} key) makes a re-run harmless, and a missed day self-heals
 * (candidates are recomputed from scratch each tick).
 *
 * <p><b>Inert when the flag is off:</b> the very first line returns when
 * {@code !props.isPayoutScheduleManual()}, so nothing is queried and no payout is
 * created until the master kill-switch is enabled in prod.
 *
 * <p>{@link SchedulerLock} serializes the tick across replicas (backed by the
 * {@code shedlock} JDBC table, already enabled by {@code SchedulingConfig}). The
 * per-event unit runs through the {@link PostEventPayoutService} proxy so its
 * {@code REQUIRES_NEW} boundary is real and one event's failure can't roll back the
 * batch; the outer try/catch is a second isolation net.
 */
@Component
public class PostEventPayoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(PostEventPayoutSweeper.class);

    private static final int BATCH_SIZE = 50;
    /** ponytail: at most 1000 candidates per tick; the rest wait a day, with a WARN. */
    private static final int MAX_PAGES = 20;

    private final StripeProperties props;
    private final EventRepository events;
    private final PayoutRunRepository payoutRuns;
    private final RefundRepository refunds;
    private final DisputeRepository disputes;
    private final PostEventPayoutService payoutService;
    private final Clock clock;

    public PostEventPayoutSweeper(StripeProperties props,
                                  EventRepository events,
                                  PayoutRunRepository payoutRuns,
                                  RefundRepository refunds,
                                  DisputeRepository disputes,
                                  PostEventPayoutService payoutService,
                                  Clock clock) {
        this.props = props;
        this.events = events;
        this.payoutRuns = payoutRuns;
        this.refunds = refunds;
        this.disputes = disputes;
        this.payoutService = payoutService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Amsterdam")
    @SchedulerLock(name = "PostEventPayoutSweeper.sweep", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void sweep() {
        if (!props.isPayoutScheduleManual()) return;   // master kill-switch — inert when off

        // ── step A — reconcile stale SUBMITTED runs BEFORE looking for candidates ──
        // A SUBMITTED run blocks every event for its org (the org-level in-flight guard) and
        // its only other exit is a connected-account payout.* webhook, which is dark whenever
        // the OPTIONAL STRIPE_WEBHOOK_SECRET_CONNECT is blank and which Stripe stops retrying
        // after ~3 days. Polling Stripe for the po_ we stored closes the loop, and doing it
        // first means an org unblocked here can still be paid in the same tick.
        reconcileStaleSubmitted();

        // ── step B — refunds and lost disputes EVERY org owes imin, and shares owed back ──
        // Before the candidate loop: the recovery inside payOneEvent only reaches orgs with a candidate.
        recoverOwedMoney();

        // Resolve the buffer deadline in the configured payout zone (the business
        // deadline, not the event's local zone): an event qualifies when
        // endsAt < (now − bufferDays) measured in payoutZone.
        ZoneId zone = ZoneId.of(props.getPayoutZone());
        Instant cutoff = ZonedDateTime.now(clock.withZone(zone))
                .minusDays(props.getPayoutBufferDays())
                .toInstant();

        // Skipped events write no run and keep their place, so page on by (endsAt, id) instead of re-reading page one.
        int paid = 0, failed = 0;
        Instant afterEndsAt = EventRepository.FIRST_ENDS_AT;
        UUID afterId = EventRepository.FIRST_ID;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Event> due = events.findPayoutCandidatesAfter(cutoff, afterEndsAt, afterId,
                    PageRequest.of(0, BATCH_SIZE));
            if (due.isEmpty()) break;
            if (page == 0) {
                log.info("[payout-sweep] candidate event(s) past endsAt+{}d (cutoff={} {})",
                        props.getPayoutBufferDays(), cutoff, zone);
            }
            for (Event e : due) {
                try {
                    payoutService.payOneEvent(e.getId());   // own REQUIRES_NEW tx
                    paid++;
                } catch (Exception ex) {
                    // payOneEvent already swallows StripeException; an escape here would be an
                    // unexpected runtime error — isolate it so one bad event can't kill the batch.
                    log.error("[payout-sweep] payout failed for event {} — {}", e.getId(), ex.getMessage(), ex);
                    failed++;
                }
            }
            Event last = due.get(due.size() - 1);
            afterEndsAt = last.getEndsAt();
            afterId = last.getId();
            if (due.size() < BATCH_SIZE) break;
            if (page == MAX_PAGES - 1
                    && !events.findPayoutCandidatesAfter(cutoff, afterEndsAt, afterId, PageRequest.of(0, 1)).isEmpty()) {
                log.warn("[payout-sweep] stopped after {} candidates; the rest wait for the next tick",
                        MAX_PAGES * BATCH_SIZE);
            }
        }
        log.info("[payout-sweep] tick done processed={} errored={}", paid, failed);
    }

    /**
     * {@link PostEventPayoutService#recoverForOrg} per org owing a refund or lost-dispute recovery, or
     * owed a return, each in its own {@code REQUIRES_NEW} transaction through the service proxy.
     */
    private void recoverOwedMoney() {
        Set<UUID> owing = new LinkedHashSet<>(refunds.findOrgIdsWithUnrecoveredPlatformFunded());
        owing.addAll(disputes.findOrgIdsOwingDisputeMoney(DisputeStatus.LOST,
                List.of(DisputeStatus.WON, DisputeStatus.WITHDRAWN_REINSTATED), !props.isLiveKey()));
        if (owing.isEmpty()) return;

        log.info("[payout-sweep] {} org(s) owe a refund or dispute recovery, or are owed a return", owing.size());
        for (UUID orgId : owing) {
            try {
                payoutService.recoverForOrg(orgId);
            } catch (Exception ex) {
                log.error("[payout-sweep] recovery failed for org {} — {}",
                        orgId, ex.getMessage(), ex);
            }
        }
    }

    /**
     * Re-read every {@code SUBMITTED} payout run older than the reconcile window from Stripe
     * and apply the {@code paid}/{@code failed} transition the webhook would have. Each run
     * reconciles in its own {@code REQUIRES_NEW} transaction through the service proxy, so
     * one unreadable payout can't abort the sweep.
     */
    private void reconcileStaleSubmitted() {
        Instant staleBefore = Instant.now(clock).minus(
                Duration.ofHours(Math.max(1, props.getPayoutReconcileAfterHours())));
        List<PayoutRun> stale = payoutRuns.findByStatusAndSubmittedAtBefore(
                PayoutRunStatus.SUBMITTED, staleBefore);
        if (stale.isEmpty()) return;

        log.info("[payout-sweep] reconciling {} SUBMITTED run(s) submitted before {}", stale.size(), staleBefore);
        for (PayoutRun r : stale) {
            try {
                payoutService.reconcileSubmittedRun(r.getId());
            } catch (Exception ex) {
                log.error("[payout-sweep] reconcile failed for run {} — {}", r.getId(), ex.getMessage(), ex);
            }
        }
    }
}
