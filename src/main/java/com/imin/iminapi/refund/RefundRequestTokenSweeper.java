package com.imin.iminapi.refund;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Periodically prunes {@link RefundRequestToken} rows whose {@code expires_at}
 * has passed by more than the retention window. Tokens are already useless past
 * their expiry (the service rejects any redeem attempt), but we keep them
 * around 7 days for audit / debugging before deleting.
 *
 * <p>Unlike {@link com.imin.iminapi.service.event.ReservationSweeper} which
 * sweeps every 60 seconds because stale holds carve out inventory, refund-
 * request tokens are cheap rows that simply accumulate — daily is enough.
 *
 * <p>{@link SchedulerLock} serializes the run across replicas. {@link Transactional}
 * scopes the bulk {@code delete} so it commits atomically.
 */
@Component
public class RefundRequestTokenSweeper {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestTokenSweeper.class);
    private static final Duration RETENTION = Duration.ofDays(7);

    private final RefundRequestTokenRepository tokens;

    public RefundRequestTokenSweeper(RefundRequestTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * One sweep tick. Runs daily at 04:07 server time — off-peak and offset
     * from common round-hour jobs to spread DB load.
     */
    @Scheduled(cron = "0 7 4 * * *")
    @SchedulerLock(name = "RefundRequestTokenSweeper.sweep", lockAtLeastFor = "PT1M", lockAtMostFor = "PT15M")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int deleted = tokens.deleteExpiredBefore(cutoff);
        log.info("[refund-request] sweep removed {} expired token(s) (cutoff={})", deleted, cutoff);
    }
}
