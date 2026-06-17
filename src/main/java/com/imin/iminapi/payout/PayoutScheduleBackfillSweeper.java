package com.imin.iminapi.payout;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.stripe.StripePayoutScheduleService;
import com.imin.iminapi.stripe.StripeProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Track B (manual payouts) Phase 1 backfill: flips every payout-eligible connected
 * account that is still on Stripe's default automatic schedule over to manual. Modeled
 * on {@link com.imin.iminapi.stripe.StripeConnectStatusSweeper}.
 *
 * <p><strong>Inert when the kill-switch is off.</strong> The tick returns at its first
 * line when {@link StripeProperties#isPayoutScheduleManual()} is false — nothing flips.
 *
 * <p><strong>Permanent, not one-shot.</strong> The connect status sweeper excludes
 * already-ACTIVE accounts from re-sync, so if the {@code capability_status_updated}
 * webhook is missed for an account that has already reached ACTIVE, the §3.3 onboarding
 * hook never re-fires for it — this sweeper is then the <em>only</em> thing that flips it
 * to manual. In steady state (all eligible accounts flipped) the candidate query returns
 * empty and the tick is a single indexed query, so it is left running indefinitely as a
 * cheap safety net.
 *
 * <p>Per-row {@code try/catch} isolates a bad account so one failure cannot kill the
 * batch (mirrors {@code StripeConnectStatusSweeper}); {@code ensureManual} additionally
 * swallows its own {@link com.stripe.exception.StripeException}. {@link SchedulerLock}
 * serializes the tick across replicas.
 */
@Component
public class PayoutScheduleBackfillSweeper {

    private static final Logger log = LoggerFactory.getLogger(PayoutScheduleBackfillSweeper.class);

    private static final int BATCH_SIZE = 100;

    private final OrganizationRepository orgs;
    private final StripePayoutScheduleService payoutScheduleService;
    private final StripeProperties props;

    public PayoutScheduleBackfillSweeper(OrganizationRepository orgs,
                                         StripePayoutScheduleService payoutScheduleService,
                                         StripeProperties props) {
        this.orgs = orgs;
        this.payoutScheduleService = payoutScheduleService;
        this.props = props;
    }

    /**
     * {@code fixedDelay=600_000} runs 10 min after the previous tick finishes;
     * {@code initialDelay=120_000} lets the app warm up first.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    @SchedulerLock(name = "PayoutScheduleBackfillSweeper.sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT9M")
    public void sweep() {
        if (!props.isPayoutScheduleManual()) return;            // inert when flag off

        List<Organization> due = orgs.findPayoutScheduleBackfillCandidates(PageRequest.of(0, BATCH_SIZE));
        if (due.isEmpty()) return;

        log.info("[payout-backfill] flipping {} payout-eligible account(s) to manual", due.size());
        int done = 0;
        int failed = 0;
        for (Organization org : due) {
            try {
                payoutScheduleService.ensureManual(org);
                done++;
            } catch (Exception e) {
                // ensureManual swallows StripeException; a throw here is unexpected — isolate
                // it so one bad row can't kill the batch.
                log.error("[payout-backfill] failed org {} acct {} — {}",
                        org.getId(), org.getStripeAccountId(), e.getMessage(), e);
                failed++;
            }
        }
        log.info("[payout-backfill] tick done set={} failed={}", done, failed);
    }
}
