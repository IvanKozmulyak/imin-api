package com.imin.iminapi.dispute;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The backstop for {@code DisputeIngestService.attachOrphansForOrder}: a dispute whose order
 * did not exist yet — or whose charge was momentarily unreadable — is matched here as soon as
 * the order lands, whatever order Stripe delivered the two webhooks in.
 *
 * <p>Bounded on both axes: only disputes created inside {@link #WINDOW} are considered, and
 * only {@link #BATCH_SIZE} of them per tick. Pure DB work, no Stripe call, so it is safe on
 * the scheduler pool shared with every other job. Idempotent — the conditional UPDATE means a
 * row already attached simply is not found again.
 */
@Component
public class DisputeAttributionSweeper {

    private static final Logger log = LoggerFactory.getLogger(DisputeAttributionSweeper.class);

    /** Older than this and the order is never going to turn up; it needs a human, not a retry. */
    private static final Duration WINDOW = Duration.ofDays(3);
    private static final int BATCH_SIZE = 200;

    private final DisputeRepository disputes;
    private final OrderRepository orders;
    private final DisputeIngestService ingest;

    public DisputeAttributionSweeper(DisputeRepository disputes,
                                     OrderRepository orders,
                                     DisputeIngestService ingest) {
        this.disputes = disputes;
        this.orders = orders;
        this.ingest = ingest;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    @SchedulerLock(name = "DisputeAttributionSweeper.sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    @Transactional
    public void sweep() {
        // created_at, not opened_at: opened_at is nullable, created_at never is.
        Instant cutoff = Instant.now().minus(WINDOW);
        // The hand-off between the passes is this set, not whatever the persistence context
        // happens to have flushed by the time pass 2 runs its query.
        Set<UUID> attachedThisTick = attachOrphans(cutoff);
        revokeAttributed(cutoff, attachedThisTick);
    }

    /**
     * Pass 1: a dispute that never found its order gets attached, and revoked on the way.
     *
     * @return the ids this tick attached, which pass 2 must not revoke a second time.
     */
    private Set<UUID> attachOrphans(Instant cutoff) {
        List<Dispute> orphans = disputes
                .findByOrderIdIsNullAndStripePaymentIntentIdIsNotNullAndCreatedAtAfter(
                        cutoff, PageRequest.of(0, BATCH_SIZE));
        if (orphans.isEmpty()) return Set.of();

        Set<UUID> attached = new HashSet<>();
        for (Dispute row : orphans) {
            Order order = orders.findByStripePaymentIntentId(row.getStripePaymentIntentId()).orElse(null);
            if (order == null) continue;   // the order still has not been written
            if (ingest.attachOrphan(row, order)) attached.add(row.getId());
        }
        if (!attached.isEmpty()) {
            log.warn("[dispute-sweep] attached {} of {} unattributed dispute(s) to their order",
                    attached.size(), orphans.size());
        }
        return attached;
    }

    /**
     * Pass 2: a withholding dispute that already carries its order but whose tickets are still
     * live. No attach path can reach such a row, so this is the only convergence. The finder
     * mirrors the revoke skip rule, so once converged it returns nothing and this writes nothing.
     */
    private void revokeAttributed(Instant cutoff, Set<UUID> attachedThisTick) {
        List<Dispute> attributed = disputes.findAttributedWithLiveTickets(
                DisputeWithholding.STATUSES, cutoff, PageRequest.of(0, BATCH_SIZE));
        if (attributed.isEmpty()) return;

        int revokedOrders = 0;
        for (Dispute row : attributed) {
            if (row.getOrderId() == null) continue;
            if (attachedThisTick.contains(row.getId())) continue;   // pass 1 already revoked it
            Order order = orders.findById(row.getOrderId()).orElse(null);
            if (order == null) continue;
            if (ingest.revokeAttributed(row, order) > 0) revokedOrders++;
        }
        if (revokedOrders > 0) {
            log.warn("[dispute-sweep] revoked tickets on {} attributed dispute(s) whose tickets "
                    + "were still live", revokedOrders);
        }
    }
}
