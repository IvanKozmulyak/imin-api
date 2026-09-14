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
import java.util.List;

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
        List<Dispute> orphans = disputes
                .findByOrderIdIsNullAndStripePaymentIntentIdIsNotNullAndCreatedAtAfter(
                        cutoff, PageRequest.of(0, BATCH_SIZE));
        if (orphans.isEmpty()) return;

        int attached = 0;
        for (Dispute row : orphans) {
            Order order = orders.findByStripePaymentIntentId(row.getStripePaymentIntentId()).orElse(null);
            if (order == null) continue;   // the order still has not been written
            if (ingest.attachOrphan(row, order)) attached++;
        }
        if (attached > 0) {
            log.warn("[dispute-sweep] attached {} of {} unattributed dispute(s) to their order",
                    attached, orphans.size());
        }
    }
}
