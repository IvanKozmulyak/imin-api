package com.imin.iminapi.predictor.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily rebuild of the persisted pacing curves (spec §7 Stage 1, task 86cav479d). Runs at 05:30
 * Europe/Amsterdam — deliberately BEFORE the 06:00 {@code ReforecastJob} so the daily
 * re-forecast reads fresh curves. ShedLock-guarded so a multi-replica deploy rebuilds once.
 *
 * <p>The whole rebuild is a single delete-all + reinsert inside {@link PacingCurveService#rebuildAll()},
 * so it is idempotent and cheap on early-platform volumes (the same recompute-and-replace shape
 * as {@code SalesTrajectoryJob}).
 */
@Component
public class PacingCurveJob {

    private static final Logger log = LoggerFactory.getLogger(PacingCurveJob.class);

    private final PacingCurveService service;

    public PacingCurveJob(PacingCurveService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 30 5 * * *", zone = "Europe/Amsterdam")
    @SchedulerLock(name = "predictor_pacing_curves", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void run() {
        try {
            int n = service.rebuildAll();
            log.info("PacingCurveJob: {} segment curve(s) persisted", n);
        } catch (Exception e) {
            log.error("PacingCurveJob: rebuild failed: {}", e.getMessage(), e);
        }
    }
}
