package com.imin.iminapi.stripe;

import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.repository.TicketTierRepository;
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
import java.util.UUID;

/**
 * Backstop for syncs the queue lost or Stripe failed; claims with backoff so a failing tier is
 * retried at most daily.
 */
@Component
public class TierStripeSyncSweeper {

    private static final Logger log = LoggerFactory.getLogger(TierStripeSyncSweeper.class);

    static final int BATCH = 25;
    static final Duration SETTLE = Duration.ofMinutes(2);
    static final Duration BASE = Duration.ofMinutes(5);
    static final Duration CEILING = Duration.ofHours(24);
    static final int ERROR_FROM_ATTEMPT = 6;
    static final List<EventStatus> STATUSES = List.of(EventStatus.DRAFT, EventStatus.LIVE);

    private final TicketTierRepository tiers;
    private final TierStripeSyncQueue queue;
    private final Clock clock;

    public TierStripeSyncSweeper(TicketTierRepository tiers, TierStripeSyncQueue queue, Clock clock) {
        this.tiers = tiers;
        this.queue = queue;
        this.clock = clock;
    }

    /** Only claims and queues, so the shared scheduler pool never waits on Stripe. */
    @Scheduled(fixedDelay = 60_000, initialDelayString = "${imin.tier-stripe-sync.sweep-initial-delay-ms:120000}")
    @SchedulerLock(name = "tier_stripe_sync_sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void sweep() {
        Instant now = clock.instant();
        List<Object[]> due = tiers.findStripeSyncSweepCandidates(
                STATUSES, now.minus(SETTLE), now, PageRequest.of(0, BATCH));
        if (due.isEmpty()) return;

        int claimed = 0;
        int failed = 0;
        for (Object[] row : due) {
            UUID id = (UUID) row[0];
            try {
                int attempts = ((Number) row[1]).intValue();
                int n = attempts + 1;
                if (tiers.claimStripeSyncSweep(id, attempts, nextAttemptAt(now, n), now) != 1) continue;
                claimed++;
                if (n >= ERROR_FROM_ATTEMPT) {
                    log.error("TierStripeSyncSweeper: tier {} still has no Stripe ids, attempt {}", id, n);
                } else {
                    log.info("TierStripeSyncSweeper: claimed tier {} for Stripe sync, attempt {}", id, n);
                }
                queue.request(id, 0);
            } catch (RuntimeException ex) {
                failed++;
                log.error("TierStripeSyncSweeper: claim failed for tier {}", id, ex);
            }
        }
        log.info("TierStripeSyncSweeper: planned={} claimed={} failed={}", due.size(), claimed, failed);
    }

    /** Due time after the n-th claim: 5 min doubling per claim, capped at 24 h. */
    static Instant nextAttemptAt(Instant now, int n) {
        Duration backoff = BASE.multipliedBy(1L << Math.min(n - 1, 9));
        return now.plus(backoff.compareTo(CEILING) > 0 ? CEILING : backoff);
    }
}
