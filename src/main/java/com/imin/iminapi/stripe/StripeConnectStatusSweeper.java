package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
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
 * Reconciliation backstop for Stripe Connect account state — the analogue of
 * {@link com.imin.iminapi.service.event.ReservationSweeper} for the onboarding mirror.
 *
 * <p>The v2 webhook ({@link StripeWebhookService}) is the fast path that updates
 * {@code organizations.stripe_*} as an organizer onboards. But a webhook can be missed
 * (endpoint down, signing-secret rotation, Stripe's fetch of the thin event failing past its
 * retries), and {@code GET /stripe/status} reads the local mirror. Without this sweeper a
 * missed webhook would freeze an org's Connect state forever — stranding the organizer on a
 * stale "Continue onboarding" banner and, because checkout gates on the mirror, silently
 * blocking buyer payments. This job re-fetches the live Stripe account for any org still in a
 * non-terminal state whose mirror has gone stale, so state always converges.
 *
 * <p>ACTIVE and DISABLED are treated as (largely) terminal and left to the webhook + the
 * checkout-time {@link StripeConnectService#getStatusLive} refresh; NOT_STARTED has no account
 * to sync. {@link SchedulerLock} serializes the tick across replicas.
 */
@Component
public class StripeConnectStatusSweeper {

    private static final Logger log = LoggerFactory.getLogger(StripeConnectStatusSweeper.class);

    private static final int BATCH_SIZE = 100;
    /** Re-reconcile a non-terminal org once its mirror is older than this. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(15);
    private static final List<StripeConnectState> RESYNC_STATES = List.of(
            StripeConnectState.ONBOARDING,
            StripeConnectState.PENDING_VERIFICATION,
            StripeConnectState.RESTRICTED);

    private final OrganizationRepository orgs;
    private final StripeConnectStatusMirror mirror;
    private final Clock clock;

    public StripeConnectStatusSweeper(OrganizationRepository orgs,
                                      StripeConnectStatusMirror mirror,
                                      Clock clock) {
        this.orgs = orgs;
        this.mirror = mirror;
        this.clock = clock;
    }

    /**
     * {@code fixedDelay=300_000} runs 5 min after the previous tick finishes; {@code
     * initialDelay=60_000} lets the app warm up first. A non-terminal org is re-synced roughly
     * every {@link #STALE_AFTER}; under normal operation (webhooks fire) the candidate set is
     * empty and the tick is a single indexed query.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @SchedulerLock(name = "StripeConnectStatusSweeper.sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void sweep() {
        var staleBefore = clock.instant().minus(STALE_AFTER);
        List<Organization> due = orgs.findConnectNeedingResync(
                RESYNC_STATES, staleBefore, PageRequest.of(0, BATCH_SIZE));
        if (due.isEmpty()) return;

        log.info("StripeConnectStatusSweeper: reconciling {} non-terminal Connect account(s)", due.size());
        int resynced = 0;
        int failed = 0;
        for (Organization org : due) {
            try {
                // syncFromStripe is idempotent and swallows upstream Stripe errors (leaving the
                // mirror untouched so this org is retried next tick). A throw here would be an
                // unexpected runtime error — isolate it so one bad row can't kill the batch.
                mirror.syncFromStripe(org.getStripeAccountId());
                resynced++;
            } catch (Exception e) {
                log.error("StripeConnectStatusSweeper: resync failed for org {} acct {} — {}",
                        org.getId(), org.getStripeAccountId(), e.getMessage(), e);
                failed++;
            }
        }
        log.info("StripeConnectStatusSweeper: tick done resynced={} failed={}", resynced, failed);
    }
}
