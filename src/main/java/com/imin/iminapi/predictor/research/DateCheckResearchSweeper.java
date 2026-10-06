package com.imin.iminapi.predictor.research;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.jobs.PredictorJobService;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import com.imin.iminapi.predictor.service.DateCheckService;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fails web research that a job can no longer finish: running with no queued or running job (an older build failed
 * it, or the tick died before the handler's clean-up), or queued longer than a job can live.
 */
@Component
public class DateCheckResearchSweeper {

    private static final Logger log = LoggerFactory.getLogger(DateCheckResearchSweeper.class);

    /** A just-queued check is left alone for this long. */
    static final Duration GRACE = Duration.ofMinutes(1);
    /** Three leases plus two release gaps is 40 min; the rest is slack. */
    static final Duration STUCK_AFTER = PredictorJobService.LOCK.multipliedBy(PredictorJobService.MAX_ATTEMPTS + 2);

    private final DateCheckRepository checks;
    private final PredictorJobRepository jobs;
    private final DateCheckService dateCheckService;
    private final PredictorProperties props;
    private final Clock clock;

    public DateCheckResearchSweeper(DateCheckRepository checks, PredictorJobRepository jobs,
                                    DateCheckService dateCheckService, PredictorProperties props, Clock clock) {
        this.checks = checks;
        this.jobs = jobs;
        this.dateCheckService = dateCheckService;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void poll() {
        if (!props.isJobsPollEnabled()) return;
        try {
            sweep();
        } catch (Exception e) {
            log.error("DateCheckResearchSweeper failed: {}: {}", e.getClass().getSimpleName(),
                    LogSafe.redact(e.getMessage()));
        }
    }

    /** Fails each stuck research once; returns how many this call failed. */
    public int sweep() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // Checks before jobs: a check and its job commit together, so any check read here has its job visible below.
        List<DateCheck> running = checks.findByResearchStatusAndResearchQueuedAtBefore(DateCheck.RESEARCH_RUNNING,
                now.minus(GRACE));
        if (running.isEmpty()) return 0;
        Set<UUID> live = new HashSet<>();
        for (String payload : jobs.findLivePayloads(DateCheckResearchJobHandler.KIND)) {
            DateCheckResearchJobHandler.dateCheckIdOf(payload).ifPresent(live::add);
        }
        Instant overdueBefore = now.minus(STUCK_AFTER);
        int swept = 0;
        for (DateCheck c : running) {
            boolean orphan = !live.contains(c.getId());
            boolean overdue = c.getResearchQueuedAt().isBefore(overdueBefore);
            if (!orphan && !overdue) continue;
            if (dateCheckService.failResearch(c.getId())) {
                swept++;
                log.warn("Research of date check {} failed by the sweep: {}", c.getId(),
                        orphan ? "no queued or running job" : "queued over " + STUCK_AFTER.toMinutes() + " min");
            }
        }
        return swept;
    }
}
