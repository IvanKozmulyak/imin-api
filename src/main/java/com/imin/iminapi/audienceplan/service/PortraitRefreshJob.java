package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.Pair;
import com.imin.iminapi.util.LogSafe;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Weekly renewal of portrait research older than 90 days, only for pairs someone requested in the last 90 days; a
 * pair nobody asked for is never researched. One batch per run, counted against the global daily cap.
 */
@Component
public class PortraitRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(PortraitRefreshJob.class);

    private final PortraitResearchStore store;
    private final PortraitResearchService research;
    private final AudiencePlanProperties props;
    private final Clock clock;

    public PortraitRefreshJob(PortraitResearchStore store, PortraitResearchService research,
                              AudiencePlanProperties props, Clock clock) {
        this.store = store;
        this.research = research;
        this.props = props;
        this.clock = clock;
    }

    /** A refresh attempt that left the row as it was is not retried before this. */
    static final Duration RETRY_AFTER = Duration.ofDays(7);

    @Scheduled(cron = "0 0 5 * * MON", zone = "Europe/Paris")
    @SchedulerLock(name = "portrait_refresh", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        // Portraits are shared by every org, so only the global kill switch applies.
        if (!props.isEnabled()) return;
        Instant now = clock.instant();
        Instant cutoff = now.minus(PortraitResearchService.TTL);
        List<Pair> due = store.dueForRefresh(cutoff, cutoff, now.minus(RETRY_AFTER), props.getPortraitRefreshBatch());
        int ready = 0;
        int empty = 0;
        int skipped = 0;
        for (Pair p : due) {
            try {
                PortraitResearchService.Outcome o = research.refresh(p.genreKey(), p.cityKey());
                if (o == PortraitResearchService.Outcome.CAPPED) {
                    log.info("PortraitRefreshJob: daily cap reached, the rest waits for the next run");
                    break;
                }
                store.markRefreshAttempted(p, now);
                if (o == PortraitResearchService.Outcome.READY) ready++;
                else if (o == PortraitResearchService.Outcome.EMPTY) empty++;
                else skipped++;
            } catch (RuntimeException e) {
                empty++;
                markQuietly(p, now);
                log.error("PortraitRefreshJob: {} / {} failed: {} {}", p.genreKey(), p.cityKey(),
                        e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
            }
        }
        log.info("PortraitRefreshJob: {} due, {} refreshed, {} without a usable answer, {} skipped", due.size(), ready,
                empty, skipped);
    }

    /** The stamp after a failure is best effort: a failing write must not hide the original error in the log. */
    private void markQuietly(Pair p, Instant now) {
        try {
            store.markRefreshAttempted(p, now);
        } catch (RuntimeException e) {
            log.warn("PortraitRefreshJob: could not stamp {} / {}: {}", p.genreKey(), p.cityKey(),
                    e.getClass().getSimpleName());
        }
    }
}
