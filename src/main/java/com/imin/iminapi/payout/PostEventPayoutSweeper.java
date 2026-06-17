package com.imin.iminapi.payout;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.stripe.StripeProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.List;

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

    private final StripeProperties props;
    private final EventRepository events;
    private final PostEventPayoutService payoutService;
    private final Clock clock;

    public PostEventPayoutSweeper(StripeProperties props,
                                  EventRepository events,
                                  PostEventPayoutService payoutService,
                                  Clock clock) {
        this.props = props;
        this.events = events;
        this.payoutService = payoutService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Amsterdam")
    @SchedulerLock(name = "PostEventPayoutSweeper.sweep", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void sweep() {
        if (!props.isPayoutScheduleManual()) return;   // master kill-switch — inert when off

        // Resolve the buffer deadline in the configured payout zone (the business
        // deadline, not the event's local zone): an event qualifies when
        // endsAt < (now − bufferDays) measured in payoutZone.
        ZoneId zone = ZoneId.of(props.getPayoutZone());
        Instant cutoff = ZonedDateTime.now(clock.withZone(zone))
                .minusDays(props.getPayoutBufferDays())
                .toInstant();

        List<Event> due = events.findPayoutCandidates(cutoff, PageRequest.of(0, BATCH_SIZE));
        if (due.isEmpty()) return;

        log.info("[payout-sweep] {} candidate event(s) past endsAt+{}d (cutoff={} {})",
                due.size(), props.getPayoutBufferDays(), cutoff, zone);

        int paid = 0, failed = 0;
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
        log.info("[payout-sweep] tick done processed={} errored={}", paid, failed);
    }
}
