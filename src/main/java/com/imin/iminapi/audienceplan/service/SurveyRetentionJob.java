package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.repository.SurveyResponseRepository;
import com.imin.iminapi.util.LogSafe;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes post-event survey answers older than {@code legal.retention_days} (3 years), the period the survey
 * notice announces. Shares {@code retention-job-enabled}: while false it only counts and logs.
 */
@Component
public class SurveyRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(SurveyRetentionJob.class);

    /** Answers past the window, and how many were deleted (0 on a dry run). */
    public record Result(boolean enabled, long expired, int deleted) {}

    private final SurveyResponseRepository responses;
    private final AudiencePlanLogic logic;
    private final AudiencePlanProperties props;
    private final Clock clock;
    /** Calls {@link #run()} through the proxy so its scheduler lock applies. */
    private final ObjectProvider<SurveyRetentionJob> self;

    public SurveyRetentionJob(SurveyResponseRepository responses, AudiencePlanLogic logic,
                              AudiencePlanProperties props, Clock clock, ObjectProvider<SurveyRetentionJob> self) {
        this.responses = responses;
        this.logic = logic;
        this.props = props;
        this.clock = clock;
        this.self = self;
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "Europe/Paris")
    public void scheduled() {
        try {
            self.getObject().run();
        } catch (Exception e) {
            log.error("SurveyRetentionJob: run failed (next night retries): {} {}",
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    @SchedulerLock(name = "survey_retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public Result run() {
        boolean enabled = Boolean.TRUE.equals(props.getRetentionJobEnabled());
        // Answers carry only their UTC day, so the cutoff is a UTC midnight too.
        Instant cutoff = clock.instant().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(logic.logic().legal().retentionDays()));
        long expired = responses.countByCreatedAtBefore(cutoff);
        if (!enabled) {
            log.info("SurveyRetentionJob: dry run, {} survey answers past the retention window, nothing deleted",
                    expired);
            return new Result(false, expired, 0);
        }
        int deleted = expired == 0 ? 0 : responses.deleteCreatedBefore(cutoff);
        log.info("SurveyRetentionJob: done, {} survey answers past the retention window, {} deleted", expired, deleted);
        return new Result(true, expired, deleted);
    }
}
