package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.dto.PredictionFeedbackRequest;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.dto.PredictionStatusResponse;
import com.imin.iminapi.predictor.dto.PredictionTriggerResponse;
import com.imin.iminapi.predictor.model.FeedbackType;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ai.AiQuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * The pre-publish score request surface (BE half of task 86cav4766): trigger, status,
 * feedback. Owns the HTTP-facing concerns the pipeline deliberately doesn't: org scoping,
 * cache short-circuit, throttle + quota ordering, and the in-flight registry.
 *
 * <p>Ordering on POST (each layer only reached when the previous passed):
 * <ol>
 *   <li>ownership (event belongs to caller's org, else 404 — no cross-org probe)</li>
 *   <li>already-pending → 202 with the in-flight id (idempotent, free)</li>
 *   <li>input-hash cache hit → 200 cached result, NO llm, NO new ledger row, NO quota</li>
 *   <li>burst throttle {@code predictor-rescore} (per user) → 429 RATE_LIMITED</li>
 *   <li>daily quota kind=score — consumed ONLY when a real LLM run is about to be
 *       dispatched (kill-switch benchmark-only runs are free) → 429 AI_QUOTA_EXCEEDED</li>
 *   <li>async dispatch → 202 pending</li>
 * </ol>
 *
 * <p>The pending registry is in-memory by design: on a restart an in-flight run is lost and
 * the status honestly falls back to the latest ledgered result (or none) — a re-POST simply
 * re-scores. Single-instance deploy (Railway), no distributed state needed.
 */
@Service
public class PredictionRequestService {

    private static final Logger log = LoggerFactory.getLogger(PredictionRequestService.class);

    private record Pending(UUID predictionId, String inputHash) {}

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private final EventRepository events;
    private final PredictionLedgerRepository ledgerRepo;
    private final PredictionLedgerService ledgerService;
    private final PredictionScoringPipeline pipeline;
    private final AiQuotaService quota;
    private final RateLimiter rateLimiter;
    private final Executor executor;

    public PredictionRequestService(EventRepository events, PredictionLedgerRepository ledgerRepo,
                                    PredictionLedgerService ledgerService, PredictionScoringPipeline pipeline,
                                    AiQuotaService quota, RateLimiter rateLimiter,
                                    @Qualifier("predictorScoreExecutor") Executor executor) {
        this.events = events;
        this.ledgerRepo = ledgerRepo;
        this.ledgerService = ledgerService;
        this.pipeline = pipeline;
        this.quota = quota;
        this.rateLimiter = rateLimiter;
        this.executor = executor;
    }

    // ---- POST /events/{id}/prediction ------------------------------------------

    public record Trigger(int httpStatus, PredictionTriggerResponse body) {}

    public Trigger trigger(AuthPrincipal p, UUID eventId) {
        Event e = loadOwned(p, eventId);

        Pending inFlight = pending.get(eventId);
        if (inFlight != null) {
            return new Trigger(202, new PredictionTriggerResponse(
                    inFlight.predictionId(), PredictionStatusResponse.STATUS_PENDING, null, null));
        }

        PredictionInputSnapshot snap = pipeline.snapshot(e);
        String hash = snap.sha256();

        PredictionLedger latest = latestRow(eventId);
        if (latest != null && hash.equals(latest.getInputSnapshotHash())) {
            PredictionResult cached = parseResult(latest);
            if (cached != null) {
                // Unchanged input → the prior ledger row IS the answer. No LLM, no new row.
                return new Trigger(200, new PredictionTriggerResponse(
                        latest.getId(), PredictionStatusResponse.STATUS_READY, true, cached));
            }
        }

        rateLimiter.consume("predictor-rescore", p.userId().toString());
        if (!pipeline.killSwitchActive()) {
            quota.checkAndRecordScore(p); // only a REAL llm run burns quota
        }

        UUID predictionId = UUID.randomUUID();
        pending.put(eventId, new Pending(predictionId, hash));
        try {
            executor.execute(() -> runScore(e, snap, eventId));
        } catch (RuntimeException ex) {
            pending.remove(eventId);
            throw ex;
        }
        return new Trigger(202, new PredictionTriggerResponse(
                predictionId, PredictionStatusResponse.STATUS_PENDING, null, null));
    }

    private void runScore(Event e, PredictionInputSnapshot snap, UUID eventId) {
        try {
            // Ledger write happens INSIDE the pipeline before it returns (write-before-render);
            // only after that does the pending flag clear and GET flip to ready.
            pipeline.score(e, snap);
        } catch (Exception ex) {
            // Pipeline degrades LLM/validator failures to benchmark-only itself; landing here
            // means infrastructure failed (e.g. DB write). Status falls back to latest/none.
            log.error("Predictor scoring run failed for event {}", eventId, ex);
        } finally {
            pending.remove(eventId);
        }
    }

    // ---- GET /events/{id}/prediction -------------------------------------------

    public PredictionStatusResponse status(AuthPrincipal p, UUID eventId) {
        loadOwned(p, eventId);

        Pending inFlight = pending.get(eventId);
        if (inFlight != null) {
            return new PredictionStatusResponse(
                    PredictionStatusResponse.STATUS_PENDING, null, inFlight.inputHash(), null);
        }

        PredictionLedger latest = latestRow(eventId);
        if (latest == null) {
            return new PredictionStatusResponse(PredictionStatusResponse.STATUS_NONE, null, null, null);
        }
        PredictionResult result = parseResult(latest);
        if (result == null) {
            // A ledger row whose output can't be re-read is treated as no renderable result —
            // never render something we can't verify against the audit record.
            log.error("Unparseable ledger output_json for row {}", latest.getId());
            return new PredictionStatusResponse(PredictionStatusResponse.STATUS_NONE, null,
                    latest.getInputSnapshotHash(), latest.getCreatedAt());
        }
        String status = result.benchmarkOnly()
                ? PredictionStatusResponse.STATUS_FAILED_BENCHMARK_ONLY
                : PredictionStatusResponse.STATUS_READY;
        return new PredictionStatusResponse(status, result, latest.getInputSnapshotHash(), latest.getCreatedAt());
    }

    // ---- POST /events/{id}/prediction/feedback -----------------------------------

    public void feedback(AuthPrincipal p, UUID eventId, PredictionFeedbackRequest req) {
        loadOwned(p, eventId);
        FeedbackType type;
        try {
            type = FeedbackType.fromWire(req.type());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "type must be one of: dismissed, executed", Map.of("type", "invalid"));
        }
        PredictionLedger latest = latestRow(eventId);
        if (latest == null) {
            throw ApiException.invalidState("No prediction exists for this event");
        }
        ledgerService.recordFeedback(latest.getId(), eventId, req.recommendationId(), type);
    }

    // ---- helpers -----------------------------------------------------------------

    private Event loadOwned(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    private PredictionLedger latestRow(UUID eventId) {
        List<PredictionLedger> rows = ledgerRepo.findByEventIdOrderByCreatedAtDesc(eventId);
        for (PredictionLedger r : rows) {
            if (r.getSurface() == PredictionSurface.PRE_PUBLISH) return r;
        }
        return null;
    }

    private PredictionResult parseResult(PredictionLedger row) {
        try {
            return PredictorJson.MAPPER.readValue(row.getOutputJson(), PredictionResult.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
