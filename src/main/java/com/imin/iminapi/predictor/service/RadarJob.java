package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.repository.EventRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Daily date-check radar: 05:50 Europe/Amsterdam, after {@code PacingCurveJob} (05:30) and before
 * {@code ReforecastJob} (06:00). Re-runs the current check of every live event whose night is 30/14/7/2 days
 * out; each event is its own transaction, so one failure never stops the pass. Dark unless both flags are on.
 */
@Component
public class RadarJob {

    private static final Logger log = LoggerFactory.getLogger(RadarJob.class);

    /** Wider than the 30-day milestone: the night is resolved per check zone and the service does the exact cut. */
    static final Duration WINDOW = Duration.ofDays(32);

    static final String RADAR_RUN_KEY = "uq_date_check_radar_run";

    record Result(int planned, int ran, int skipped, int failed) {}

    private final DateCheckProperties props;
    private final EventRepository events;
    private final DateCheckService service;
    private final Clock clock;

    public RadarJob(DateCheckProperties props, EventRepository events, DateCheckService service, Clock clock) {
        this.props = props;
        this.events = events;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "0 50 5 * * *", zone = "Europe/Amsterdam")
    @SchedulerLock(name = "predictor_radar_daily", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void run() {
        pass();
    }

    Result pass() {
        if (!Boolean.TRUE.equals(props.getEnabled()) || !Boolean.TRUE.equals(props.getRadarEnabled())) {
            return new Result(0, 0, 0, 0);
        }
        Instant now = clock.instant();
        List<UUID> ids = events.findRadarCandidateIds(now, now.plus(WINDOW));
        int ran = 0;
        int skipped = 0;
        int failed = 0;
        Exception last = null;
        for (UUID id : ids) {
            try {
                if (service.radarRerun(id) == DateCheckService.RadarOutcome.RAN) ran++;
                else skipped++;
            } catch (Exception ex) {
                if (ex instanceof DataIntegrityViolationException && namesRadarRunKey(ex)) {
                    // Another writer already stored this run from the same baseline.
                    skipped++;
                    continue;
                }
                failed++;
                last = ex;
                log.warn("RadarJob: re-run failed for event {}", id, ex);
            }
        }
        Result r = new Result(ids.size(), ran, skipped, failed);
        if (failed > 0 && ran == 0) {
            log.error("RadarJob: planned={} ran={} skipped={} failed={}", r.planned(), ran, skipped, failed, last);
        } else if (failed > 0) {
            log.warn("RadarJob: planned={} ran={} skipped={} failed={}", r.planned(), ran, skipped, failed);
        } else {
            log.info("RadarJob: planned={} ran={} skipped={} failed={}", r.planned(), ran, skipped, failed);
        }
        return r;
    }

    /** True when the cause chain names the radar run's unique key; any other violation is a real failure. */
    static boolean namesRadarRunKey(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && m.toLowerCase(Locale.ROOT).contains(RADAR_RUN_KEY)) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }
}
