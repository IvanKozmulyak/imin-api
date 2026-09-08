package com.imin.iminapi.service.analytics;

import com.imin.iminapi.repository.FunnelEventRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes funnel beacon rows older than {@code imin.analytics.funnel-retention-days}.
 *
 * <p>{@code event_funnel_events} had no expiry: the /track beacon has been
 * writing PAGE_VIEW and CHECKOUT_START rows since V41 and nothing has ever
 * removed one. Because {@code anon_id} is deliberately carried onto the order
 * (V62), those rows are joinable to a named purchaser, so "we keep browsing
 * history indefinitely" is a statement about identified people, not about
 * anonymous audience measurement.
 *
 * <p>Nothing downstream needs the history. Every reader is either a per-event
 * count or a rolling window, and all of them default an absent stage to zero —
 * so a purged event's funnel reads 0/0/paid rather than failing.
 *
 * <p>03:15 UTC: after {@code AudienceBackfillJob} (03:00) rather than alongside
 * it, so two ShedLock jobs are not competing for the same connection pool at
 * the same minute.
 */
@Component
public class FunnelRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(FunnelRetentionJob.class);

    private final FunnelEventRepository funnelEvents;
    private final AnalyticsProperties properties;
    private final Clock clock;

    public FunnelRetentionJob(FunnelEventRepository funnelEvents,
                              AnalyticsProperties properties,
                              Clock clock) {
        this.funnelEvents = funnelEvents;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * *")
    @SchedulerLock(name = "funnel_retention", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    @Transactional
    public void run() {
        int days = properties.getFunnelRetentionDays();
        if (days <= 0) {
            log.info("FunnelRetentionJob: retention disabled (funnel-retention-days={})", days);
            return;
        }
        Instant cutoff = clock.instant().minus(days, ChronoUnit.DAYS);
        int deleted = funnelEvents.deleteCreatedBefore(cutoff);
        log.info("FunnelRetentionJob: deleted {} funnel rows older than {} ({} day retention)",
                deleted, cutoff, days);
    }
}
