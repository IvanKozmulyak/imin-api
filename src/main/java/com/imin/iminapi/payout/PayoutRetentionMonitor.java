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
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Track B (manual payouts) Phase 2 — stranded-funds / retention-deadline monitor
 * (plan §7, a SHIP REQUIREMENT, not deferred).
 *
 * <p>Once an account is flipped to a MANUAL payout schedule, Stripe requires funds be
 * disbursed within a retention window (~90 days most countries). The Phase-1 backfill
 * flips balances to manual on deploy, which STARTS that clock immediately for every
 * existing eligible org — so this monitor must be live alongside the payout job, not
 * added later. If an event's funds never become payable (org disabled, dispute frozen,
 * a persistently-failing payout) they can strand past the window.
 *
 * <p>Each day it finds events that ended more than {@link #RETENTION_BOUND} ago, still
 * carry sold revenue, belong to a manual-schedule eligible org, and have NO {@code PAID}
 * payout run — i.e. funds the org is likely still holding past the safe bound — and logs
 * a WARN alert per event so ops can force a payout before Stripe's deadline. It moves no
 * money and writes nothing; it is a read-only alarm.
 *
 * <p><b>Inert when the flag is off:</b> returns at the first line when
 * {@code !props.isPayoutScheduleManual()}.
 */
@Component
public class PayoutRetentionMonitor {

    private static final Logger log = LoggerFactory.getLogger(PayoutRetentionMonitor.class);

    private static final int BATCH_SIZE = 200;
    /**
     * 75 days — a ~15-day margin under the common 90-day Stripe retention window, so
     * ops have time to act before funds strand. Tune alongside
     * {@code STRIPE_PAYOUT_BUFFER_DAYS} if a market's window differs.
     */
    private static final Duration RETENTION_BOUND = Duration.ofDays(75);

    private final StripeProperties props;
    private final EventRepository events;
    private final PayoutRunRepository payoutRuns;
    private final Clock clock;

    public PayoutRetentionMonitor(StripeProperties props,
                                  EventRepository events,
                                  PayoutRunRepository payoutRuns,
                                  Clock clock) {
        this.props = props;
        this.events = events;
        this.payoutRuns = payoutRuns;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Europe/Amsterdam")
    @SchedulerLock(name = "PayoutRetentionMonitor.scan", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void scan() {
        if (!props.isPayoutScheduleManual()) return;   // inert when off

        Instant retentionCutoff = clock.instant().minus(RETENTION_BOUND);
        List<Event> candidates = events.findRetentionMonitorCandidates(
                retentionCutoff, PageRequest.of(0, BATCH_SIZE));
        if (candidates.isEmpty()) return;

        int stranded = 0;
        for (Event e : candidates) {
            // A PAID run means the funds were disbursed — not stranded. Anything else
            // (no run, PLANNED/SUBMITTED stuck, FAILED) for an event this old past the
            // bound is an alert: ops must force a payout before Stripe's window closes.
            if (payoutRuns.existsByEventIdAndStatus(e.getId(), PayoutRunStatus.PAID)) continue;
            stranded++;
            log.warn("[payout-retention] STRANDED FUNDS: event {} org {} ended {} (>{} d ago) revenue={} {} "
                            + "has no PAID payout run — force a payout before Stripe's retention window closes",
                    e.getId(), e.getOrgId(), e.getEndsAt(), RETENTION_BOUND.toDays(),
                    e.getRevenueMinor(), e.getCurrency());
        }
        if (stranded > 0) {
            log.warn("[payout-retention] scan flagged {} event(s) with funds past the {}-day retention bound",
                    stranded, RETENTION_BOUND.toDays());
        }
    }
}
