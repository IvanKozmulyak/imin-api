package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.LanguageTier;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.PredictorSegmentStatus;
import com.imin.iminapi.predictor.repository.PredictorSegmentStatusRepository;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot.CorpusLine;
import com.imin.iminapi.predictor.service.Stage0Scorer.Stage0Output;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Stage 0 scoring pipeline (spec §7.2):
 * {@code feature assembly → comparable retrieval → LLM structured call → guardrail validator
 * → ledger write → render}.
 *
 * <p>Non-negotiables enforced here:
 * <ul>
 *   <li><b>Write-before-render</b>: every path out of {@link #score} — validated, retried,
 *       or benchmark-only — records a ledger row BEFORE returning.</li>
 *   <li><b>Unvalidated numbers never reach a screen</b>: an LLM output is rendered only if
 *       the deterministic validator passed it; one retry with the errors fed back; a second
 *       failure (or any transport failure) degrades to benchmark-only.</li>
 *   <li><b>Kill switch</b> ({@code imin.predictor.benchmark-only}) checked at request time:
 *       benchmark-only result, no LLM call.</li>
 *   <li><b>Language tier</b> is computed from corpus density (§5 ladder) and the segment's
 *       tripwire overrides — never by the model.</li>
 * </ul>
 */
@Service
public class PredictionScoringPipeline {

    private static final Logger log = LoggerFactory.getLogger(PredictionScoringPipeline.class);

    /** A completed scoring run: the ledgered result and its identity. */
    public record Scored(UUID ledgerId, PredictionResult result, String inputHash) {}

    private final PredictionInputSnapshotService snapshots;
    private final Stage0Scorer scorer;
    private final PredictionGuardrailValidator validator;
    private final RecommendationEngine recommendations;
    private final PredictionLedgerService ledger;
    private final PredictorSegmentStatusRepository segmentStatus;
    private final PredictorProperties props;
    private final Clock clock;

    public PredictionScoringPipeline(PredictionInputSnapshotService snapshots, Stage0Scorer scorer,
                                     PredictionGuardrailValidator validator, RecommendationEngine recommendations,
                                     PredictionLedgerService ledger,
                                     PredictorSegmentStatusRepository segmentStatus,
                                     PredictorProperties props, Clock clock) {
        this.snapshots = snapshots;
        this.scorer = scorer;
        this.validator = validator;
        this.recommendations = recommendations;
        this.ledger = ledger;
        this.segmentStatus = segmentStatus;
        this.props = props;
        this.clock = clock;
    }

    /** Build the snapshot only (cache-key probe — no LLM, no ledger). */
    public PredictionInputSnapshot snapshot(Event e) {
        return snapshots.build(e);
    }

    /** True when the global kill switch forces benchmark-only mode (checked per request). */
    public boolean killSwitchActive() {
        return props.isBenchmarkOnly();
    }

    /** Overload for callers that do not label the trigger (defaults to unlabeled). */
    public Scored score(Event e, PredictionInputSnapshot snap) {
        return score(e, snap, null);
    }

    /**
     * Run one full Stage 0 score for a pre-built snapshot. The caller (request service) has
     * already handled cache short-circuit, quota and throttling — by the time we are here,
     * an LLM call is intended unless the kill switch or double validation failure says
     * otherwise. {@code trigger} (e.g. "manual", "edit") is stamped into the ledger row's
     * metadata so system re-scores are distinguishable from organizer requests (§7.3).
     */
    public Scored score(Event e, PredictionInputSnapshot snap, String trigger) {
        String hash = snap.sha256();
        PredictorSegmentStatus seg = segmentStatus
                .findById(PredictorSegmentStatus.key(snap.genreFamily(), bandOf(snap))).orElse(null);
        LanguageTier tier = earnedTier(snap.comparables());
        if (seg != null && seg.dropsOneTier()) {
            tier = tier.dropOne(); // §5 automatic downgrade (tripwire), applied at render time
        }
        boolean qualitativeOverride = seg != null && seg.qualitativeOnly();

        if (killSwitchActive()) {
            return ledgered(e, snap, hash, benchmarkOnly(snap, tier), "kill-switch", trigger);
        }

        PredictionGuardrailValidator.Context ctx = context(snap);
        List<String> firstErrors = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Stage0Output out;
            try {
                out = scorer.score(snap, tier, firstErrors);
            } catch (Exception ex) {
                log.warn("Stage 0 scoring attempt {} failed for event {}: {}", attempt, e.getId(), ex.getMessage());
                if (attempt == 2 || firstErrors != null) break;
                firstErrors = List.of("previous attempt produced no parseable JSON for the requested schema");
                continue;
            }
            List<String> errors = validator.validate(out, ctx);
            if (errors.isEmpty()) {
                PredictionResult result = assemble(e, out, snap, tier, qualitativeOverride);
                return ledgered(e, snap, hash, result, null, trigger);
            }
            log.warn("Guardrail validator rejected attempt {} for event {}: {}", attempt, e.getId(), errors);
            firstErrors = errors;
        }

        // Second failure → benchmark-only (the floor, not failure §5). STILL LEDGERED.
        return ledgered(e, snap, hash, benchmarkOnly(snap, tier), "validator-double-failure", trigger);
    }

    // ---- tier ladder (§5) ------------------------------------------------------

    /**
     * The earned tier from corpus density: C below {@code tierBMinDensity}, A from
     * {@code tierAMinDensity} with ≥ {@code tierAOwnMin} own events, B between.
     * Thresholds are config — honest guesses until the ledger tunes them.
     */
    public LanguageTier earnedTier(CorpusLine corpus) {
        int density = corpus == null ? 0 : corpus.densityTotal();
        int own = corpus == null ? 0 : corpus.ownCount();
        if (density < props.getTierBMinDensity()) return LanguageTier.C;
        if (density >= props.getTierAMinDensity() && own >= props.getTierAOwnMin()) return LanguageTier.A;
        return LanguageTier.B;
    }

    // ---- assembly ---------------------------------------------------------------

    private PredictionResult assemble(Event e, Stage0Output out, PredictionInputSnapshot snap,
                                      LanguageTier tier, boolean qualitativeOverride) {
        return new PredictionResult(
                PredictionSurface.PRE_PUBLISH.wire(),
                0,
                tier.wire(),
                out.selloutBand(),
                qualitativeOverride ? null : out.attendanceRange(),   // MAPE tripwire: numeric → qualitative
                qualitativeOverride ? null : out.revenueRangeMinor(),
                out.factors(),
                // Normalize raw candidates → impact-ranked, tier-resolved, momentum-linked, ≤3.
                recommendations.finalizeForRender(e.getId(), out.recommendations()),
                comparables(snap),
                false,
                scorer.modelId(),
                Stage0Scorer.PROMPT_VERSION,
                clock.instant());
    }

    /**
     * The benchmark-only floor (§5): comparable-segment stats, NO forward-looking numbers,
     * no factors (nothing generated). Deterministic — never validated, never fabricated.
     */
    PredictionResult benchmarkOnly(PredictionInputSnapshot snap, LanguageTier tier) {
        return new PredictionResult(
                PredictionSurface.PRE_PUBLISH.wire(),
                0,
                tier.wire(),
                null, null, null,
                List.of(),
                List.of(),
                comparables(snap),
                true,
                scorer.modelId(),
                Stage0Scorer.PROMPT_VERSION,
                clock.instant());
    }

    /**
     * FE-aligned comparables block: {@code filters} is a language-neutral display string
     * ("techno · 101–300 · summer · Amsterdam"), relaxation-aware — city only while geography
     * is un-relaxed, season omitted once dropped. {@code aggregates} is a flat display map;
     * foreign entries only when the ≥5 privacy floor was met (already-rounded figures).
     */
    private PredictionResult.Comparables comparables(PredictionInputSnapshot snap) {
        CorpusLine c = snap.comparables();
        boolean countryWide = !"NONE".equals(c.relaxation());
        boolean seasonDropped = "DROP_SEASON".equals(c.relaxation());

        StringBuilder filters = new StringBuilder();
        appendPart(filters, snap.genreFamily());
        appendPart(filters, bandLabel(snap.capacityBand()));
        if (!seasonDropped && snap.season() != null) appendPart(filters, snap.season().toLowerCase());
        appendPart(filters, countryWide ? snap.country() : snap.city());

        Map<String, Object> aggregates = new LinkedHashMap<>();
        aggregates.put("events", c.densityTotal());
        aggregates.put("own", c.ownCount());
        if (c.foreignAggregate() != null) {
            PredictionInputSnapshot.ForeignAggregateLine fa = c.foreignAggregate();
            aggregates.put("avgAttendance", fa.avgAttendanceRounded());
            aggregates.put("medianAttendance", fa.medianAttendanceRounded());
            aggregates.put("avgRevenueMinor", fa.avgRevenueMinorRounded());
            aggregates.put("sellOutRate", Math.round(fa.sellOutRate() * 100) + "%");
        }

        return new PredictionResult.Comparables(
                c.densityTotal(), c.ownCount(), c.relaxation(), filters.toString(), aggregates);
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(part);
    }

    /** Language-neutral capacity-band label (numbers + symbols only). */
    private static String bandLabel(String band) {
        if (band == null) return null;
        return switch (band) {
            case "LE100" -> "≤100";
            case "B101_300" -> "101–300";
            case "B301_800" -> "301–800";
            case "GT800" -> "800+";
            default -> band;
        };
    }

    // ---- ledger (write-before-render) -------------------------------------------

    private Scored ledgered(Event e, PredictionInputSnapshot snap, String hash,
                            PredictionResult result, String degradeReason, String trigger) {
        if (degradeReason != null) {
            log.warn("Predictor degraded to benchmark-only for event {} ({})", e.getId(), degradeReason);
        }
        String comparablesJson = comparablesLedgerJson(snap.comparables(), trigger);
        String outputJson;
        try {
            outputJson = PredictorJson.MAPPER.writeValueAsString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("PredictionResult serialization failed", ex);
        }
        UUID ledgerId = ledger.record(new PredictionLedgerService.RecordCommand(
                e.getId(), e.getOrgId(), PredictionSurface.PRE_PUBLISH, 0,
                result.modelId(), result.promptVersion(), hash, comparablesJson, outputJson));
        return new Scored(ledgerId, result, hash);
    }

    /**
     * Ledger comparables: own ids + cluster stats + relaxation, plus the {@code trigger} that
     * caused this render (task §4: system re-scores stamp "edit"/"manual" so they are
     * distinguishable in the ledger, mirroring how the re-forecast ledger stamps its trigger).
     * Foreign ids NEVER (privacy §6.4).
     */
    private String comparablesLedgerJson(CorpusLine c, String trigger) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clusterSize", c.densityTotal());
        m.put("ownCount", c.ownCount());
        m.put("foreignCount", c.foreignCount());
        m.put("relaxation", c.relaxation());
        if (trigger != null && !trigger.isBlank()) m.put("trigger", trigger);
        m.put("ownIds", c.ownEvents().stream().map(PredictionInputSnapshot.OwnComparableLine::eventId).toList());
        try {
            return PredictorJson.MAPPER.writeValueAsString(m);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static CapacityBand bandOf(PredictionInputSnapshot snap) {
        return snap.capacityBand() == null ? null : CapacityBand.valueOf(snap.capacityBand());
    }

    private PredictionGuardrailValidator.Context context(PredictionInputSnapshot snap) {
        Integer min = null, max = null;
        for (PredictionInputSnapshot.TierLine t : snap.tiers()) {
            if (min == null || t.priceMinor() < min) min = t.priceMinor();
            if (max == null || t.priceMinor() > max) max = t.priceMinor();
        }
        return new PredictionGuardrailValidator.Context(
                snap.capacity() == null ? 0 : snap.capacity(), min, max, clock.instant());
    }
}
