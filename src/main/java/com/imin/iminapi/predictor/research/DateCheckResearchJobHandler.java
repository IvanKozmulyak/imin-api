package com.imin.iminapi.predictor.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.imin.iminapi.predictor.config.DateCheckAccess;
import com.imin.iminapi.predictor.jobs.PredictorJobHandler;
import com.imin.iminapi.predictor.jobs.PredictorJobService;
import com.imin.iminapi.predictor.model.PredictorJob;
import com.imin.iminapi.predictor.service.DateCheckService;
import com.imin.iminapi.predictor.service.PredictorJson;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Runs a check's web research ({@code {"dateCheckId": ...}}). Idempotent: a check whose research is no longer running
 * is skipped, so a re-delivered job makes no second call. An org taken off the research list meanwhile fails the
 * research without a call. When the last attempt throws, the research is marked failed before the error is rethrown,
 * and a lease that expires on the last attempt marks it failed through {@link #onTerminalFailure}, so the check never
 * stays running.
 */
@Component
public class DateCheckResearchJobHandler implements PredictorJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DateCheckResearchJobHandler.class);

    public static final String KIND = "date_check_research";

    private final DateCheckService checks;
    private final WebResearchService research;
    private final DateCheckAccess access;

    public DateCheckResearchJobHandler(DateCheckService checks, WebResearchService research, DateCheckAccess access) {
        this.checks = checks;
        this.research = research;
        this.access = access;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void run(PredictorJob job) {
        UUID id = dateCheckId(job);
        try {
            Optional<WebResearchService.Request> req = checks.researchSnapshot(id);
            if (req.isEmpty()) return;
            WebResearchService.Outcome outcome = access.isResearchEnabled(req.get().orgId())
                    ? research.research(req.get())
                    : WebResearchService.Outcome.failed("gate_closed", null, null);
            checks.completeResearch(id, outcome);
        } catch (RuntimeException e) {
            if (job.getAttempts() >= PredictorJobService.MAX_ATTEMPTS) {
                try {
                    checks.failResearch(id);
                } catch (RuntimeException rollback) {
                    // Must not replace the error that ended the job.
                    log.error("Could not mark research of date check {} failed: {}: {}", id,
                            rollback.getClass().getSimpleName(), LogSafe.redact(rollback.getMessage()));
                    e.addSuppressed(rollback);
                }
            }
            throw e;
        }
    }

    @Override
    public void onTerminalFailure(PredictorJob job) {
        UUID id = dateCheckId(job);
        if (checks.failResearch(id)) log.warn("Research of date check {} failed: its job ran out of attempts", id);
    }

    private static UUID dateCheckId(PredictorJob job) {
        try {
            JsonNode payload = PredictorJson.MAPPER.readTree(job.getPayloadJson());
            return UUID.fromString(payload.path("dateCheckId").asText());
        } catch (Exception e) {
            throw new IllegalArgumentException("date_check_research job " + job.getId() + " has no dateCheckId", e);
        }
    }
}
