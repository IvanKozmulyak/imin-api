package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.service.AudienceBackfillCompleted;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureTarget;
import com.imin.iminapi.util.LogSafe;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Nightly full recompute (classes age by the day), chained on the audience backfill (03:00 and each deploy).
 * The 05:30 Paris fallback runs only when some live membership was not refreshed in 24 h.
 */
@Component
public class FanFeatureRecomputeJob {

    private static final Logger log = LoggerFactory.getLogger(FanFeatureRecomputeJob.class);

    static final int PAGE_SIZE = 500;
    static final Duration FRESHNESS = Duration.ofHours(24);

    private final FanFeatureRepository features;
    private final FanFeatureProjector projector;
    private final Clock clock;
    /** Calls {@link #recomputeAll()} through the proxy so its scheduler lock applies. */
    private final ObjectProvider<FanFeatureRecomputeJob> self;

    public FanFeatureRecomputeJob(FanFeatureRepository features,
                                  FanFeatureProjector projector,
                                  Clock clock,
                                  ObjectProvider<FanFeatureRecomputeJob> self) {
        this.features = features;
        this.projector = projector;
        this.clock = clock;
        this.self = self;
    }

    /** On its own single-thread pool, off the backfill thread, so the backfill's lock is released as soon as it finishes. */
    @EventListener
    @Async(FanFeatureExecutors.RECOMPUTE)
    public void onBackfillCompleted(AudienceBackfillCompleted event) {
        try {
            self.getObject().recomputeAll();
        } catch (Exception e) {
            log.error("FanFeatureRecomputeJob: recompute after backfill failed (fallback will retry): {} {}",
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    @Scheduled(cron = "0 30 5 * * *", zone = "Europe/Paris")
    public void fallback() {
        long stale = features.countStale(clock.instant().minus(FRESHNESS));
        if (stale == 0) {
            log.info("FanFeatureRecomputeJob: fallback skipped, every membership refreshed in the last 24h");
            return;
        }
        log.info("FanFeatureRecomputeJob: fallback running, {} memberships not refreshed in 24h", stale);
        self.getObject().recomputeAll();
    }

    @SchedulerLock(name = "fan_feature_recompute", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void recomputeAll() {
        long started = System.nanoTime();
        int seen = 0;
        int written = 0;
        UUID after = null;
        while (true) {
            PageRequest page = PageRequest.of(0, PAGE_SIZE);
            List<FanFeatureTarget> batch = after == null
                    ? features.findTargetsFirstPage(page)
                    : features.findTargetsAfter(after, page);
            if (batch.isEmpty()) break;
            seen += batch.size();
            written += projector.recomputeBatch(batch);
            after = batch.get(batch.size() - 1).membershipId();
            if (batch.size() < PAGE_SIZE) break;
        }
        log.info("FanFeatureRecomputeJob: done, {} of {} memberships written in {} ms",
                written, seen, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
}
