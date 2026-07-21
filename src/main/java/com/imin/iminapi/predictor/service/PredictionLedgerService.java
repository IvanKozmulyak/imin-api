package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.model.FeedbackType;
import com.imin.iminapi.predictor.model.PredictionFeedback;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.repository.PredictionFeedbackRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The prediction ledger writer (spec §5, §7.2) — one of the four never-cuttable items.
 *
 * <p><b>WRITE-BEFORE-RENDER IS A CONTRACT.</b> Every predictor surface (pre-publish score,
 * live re-forecast, prescriptive actions) MUST call {@link #record(RecordCommand)} and hold
 * the returned ledger-row id BEFORE returning any prediction to a client. An unlogged
 * prediction is a bug by definition (§5): the ledger is simultaneously the audit trail, the
 * evaluation set, and the future training set, and is the only reason shipping an LLM
 * predictor on day one is defensible — from the first real event, accuracy is measured, not
 * argued. Do NOT add a code path that renders a prediction without first calling record(...).
 *
 * <p>Versioning discipline (§7.3): {@code modelId} + {@code promptVersion} (semver) are
 * mandatory on every row. A prompt change is a version bump or eval comparability dies.
 */
@Service
public class PredictionLedgerService {

    private final PredictionLedgerRepository ledger;
    private final PredictionFeedbackRepository feedback;

    public PredictionLedgerService(PredictionLedgerRepository ledger,
                                   PredictionFeedbackRepository feedback) {
        this.ledger = ledger;
        this.feedback = feedback;
    }

    /**
     * The write-before-render command. {@code comparablesJson} and {@code outputJson} are
     * stored verbatim — callers serialize with {@link PredictorJson} (or pass {@code null}
     * for the empty-object default). {@code stage}: 0 LLM, 1 pacing, 2 learned.
     */
    public record RecordCommand(
            UUID eventId,
            UUID orgId,
            PredictionSurface surface,
            int stage,
            String modelId,
            String promptVersion,
            String inputSnapshotHash,
            String comparablesJson,
            String outputJson) {}

    /**
     * Persist a rendered prediction and return its ledger-row id. Call this BEFORE the
     * surface returns the prediction to the client (the contract above).
     *
     * @return the id of the persisted {@link PredictionLedger} row — never null.
     */
    @Transactional
    public UUID record(RecordCommand cmd) {
        PredictionLedger row = new PredictionLedger();
        row.setEventId(cmd.eventId());
        row.setOrgId(cmd.orgId());
        row.setSurface(cmd.surface());
        row.setStage((short) cmd.stage());
        row.setModelId(cmd.modelId());
        row.setPromptVersion(cmd.promptVersion());
        row.setInputSnapshotHash(cmd.inputSnapshotHash());
        row.setComparablesJson(cmd.comparablesJson() == null ? "{}" : cmd.comparablesJson());
        row.setOutputJson(cmd.outputJson() == null ? "{}" : cmd.outputJson());
        row.setCreatedAt(Instant.now());
        return ledger.save(row).getId();
    }

    /**
     * Fill a ledger row's outcome-join columns once its event has completed. Called by the
     * monthly {@link PredictionScoringJob}. Scoring math (brier/ape) may be null here — the
     * real formulas arrive with the Score phase; this wires the join so nothing has to be
     * back-migrated later.
     */
    @Transactional
    public void joinOutcome(UUID ledgerId, Integer actualSold, Integer actualAttendance,
                            Instant when, BigDecimal brierComponent, BigDecimal ape) {
        ledger.findById(ledgerId).ifPresent(row -> {
            row.setActualSold(actualSold);
            row.setActualAttendance(actualAttendance);
            row.setOutcomeJoinedAt(when);
            row.setBrierComponent(brierComponent);
            row.setApe(ape);
            ledger.save(row);
        });
    }

    /**
     * Log a dismissal or execution of one recommendation from a ledger render (§4.3).
     * Persisted as a child of the ledger row so it is "visible in the ledger" and lets
     * later phases suppress dismissed recommendations / re-forecast after an execution.
     *
     * @return the id of the persisted {@link PredictionFeedback} row.
     */
    @Transactional
    public UUID recordFeedback(UUID ledgerId, UUID eventId, String recommendationId, FeedbackType type) {
        PredictionFeedback fb = new PredictionFeedback();
        fb.setLedgerId(ledgerId);
        fb.setEventId(eventId);
        fb.setRecommendationId(recommendationId);
        fb.setFeedbackType(type);
        fb.setCreatedAt(Instant.now());
        return feedback.save(fb).getId();
    }
}
