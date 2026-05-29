package com.imin.iminapi.stripe;

import com.imin.iminapi.repository.ProcessedWebhookEventRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Prunes the {@code processed_webhook_events} dedup table. Every accepted webhook id is recorded
 * there forever otherwise, so the table grows monotonically with payment/refund/expiry volume.
 * Stripe only retries a webhook for ~3 days, so a dedup marker older than the retention window
 * can never gate a real replay — it's safe to delete.
 *
 * <p>Daily, ShedLock-serialized across replicas, mirroring {@code RefundRequestTokenSweeper}.
 */
@Component
public class ProcessedWebhookEventSweeper {

    private static final Logger log = LoggerFactory.getLogger(ProcessedWebhookEventSweeper.class);
    /** Well beyond Stripe's ~3-day retry window. */
    private static final Duration RETENTION = Duration.ofDays(30);

    private final ProcessedWebhookEventRepository events;

    public ProcessedWebhookEventSweeper(ProcessedWebhookEventRepository events) {
        this.events = events;
    }

    /** Daily at 04:17 server time — offset from the other daily sweepers to spread DB load. */
    @Scheduled(cron = "0 17 4 * * *")
    @SchedulerLock(name = "ProcessedWebhookEventSweeper.sweep", lockAtLeastFor = "PT1M", lockAtMostFor = "PT15M")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int deleted = events.deleteProcessedBefore(cutoff);
        log.info("[webhook-dedup] sweep removed {} processed-event row(s) older than 30d (cutoff={})",
                deleted, cutoff);
    }
}
