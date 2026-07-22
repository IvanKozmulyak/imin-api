package com.imin.iminapi.predictor.dto;

import java.time.Instant;
import java.util.List;

/**
 * THE shared prediction contract (spec §7.1, task 86cav474p): every stage — 0 LLM-reasoned,
 * 1 deterministic pacing, 2 learned model — produces exactly this shape, so surfaces never
 * know which brain produced it. This is also the FROZEN wire shape the frontend codes
 * against (task 86cav4766); do not rename or remove fields. {@code priceMinor}/{@code dateIso}
 * on a recommendation are additive optional fields (the guardrail validator needs them to
 * bound price/date suggestions numerically; clients may ignore them).
 *
 * <p>Trust rules baked into the shape (spec §5): probabilities are BANDS, attendance and
 * revenue are RANGES — there is nowhere to put a point estimate. {@code benchmarkOnly=true}
 * means no forward-looking numbers at all (corpus aggregates only) — the floor, not failure.
 *
 * <p><b>Recommendation contract amended (2026-07-22, task 86cav479z/86cav479w/86cav47a5):</b>
 * a served {@link Recommendation} now carries an {@code impact} tag (HIGH|MED, list ordered
 * impact-descending) and a STRUCTURED {@link ActionTarget} (resolved {@code tierId}, folded
 * {@code suggestedPriceMinor}/{@code suggestedDateIso}, and a {@code momentumSuggestionId}
 * deep-link for campaign actions) in place of the old free-text {@code actionTarget}. The raw
 * LLM emits {@link com.imin.iminapi.predictor.service.Stage0Scorer.RecCandidate}; the
 * {@code RecommendationEngine} normalizes it into this shape before ledger + serve.
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PredictionResult(
        String surface,               // pre_publish | reforecast | actions
        int stage,                    // 0 | 1 | 2
        String confidenceTier,        // A | B | C (earned by data density, §5 ladder)
        Band selloutBand,             // nullable (absent when benchmark-only)
        Range attendanceRange,        // nullable (absent when benchmark-only or qualitative override)
        LongRange revenueRangeMinor,  // nullable (absent when benchmark-only or qualitative override)
        List<Factor> factors,         // 3–5 for a scored result; empty for benchmark-only
        List<Recommendation> recommendations, // ≤ 3, may be empty
        Comparables comparables,
        boolean benchmarkOnly,
        String modelId,
        String promptVersion,
        Instant generatedAt
) {

    /** Copy with a filtered/re-ordered recommendation list (serve-time dismissal filtering). */
    public PredictionResult withRecommendations(List<Recommendation> recs) {
        return new PredictionResult(surface, stage, confidenceTier, selloutBand, attendanceRange,
                revenueRangeMinor, factors, recs, comparables, benchmarkOnly, modelId, promptVersion,
                generatedAt);
    }

    /** Probability band in whole percent, e.g. 55–75. */
    public record Band(int lowPct, int highPct) {}

    /** Integer range (attendance). */
    public record Range(int low, int high) {}

    /** Long range (revenue in minor units). */
    public record LongRange(long low, long high) {}

    /** One assessment factor. direction: supporting | opposing. Evidence is mandatory (§5). */
    public record Factor(String text, String direction, String evidence) {}

    /**
     * One served setup recommendation (spec §4.3; tasks 86cav479w/86cav479z/86cav47a5).
     *
     * <p>{@code impact}: HIGH | MED — the recommendation list is ordered impact-descending
     * (all HIGH before any MED), deterministic tiebreak by id.
     *
     * <p>{@code actionType}: the closed set the FE deep-links against — {@code campaign} (routes
     * into the momentum surface), {@code tier_edit}, {@code tier_add}, {@code tier_transition},
     * {@code promo_create}, {@code capacity}, {@code announce}. {@code actionTarget} is the
     * structured deep-link payload; null when the model gave no resolvable target.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Recommendation(String id, String claim, String evidence,
                                 String impact, String actionType, ActionTarget actionTarget) {}

    /**
     * Structured deep-link payload for a recommendation (BE of task 86cav479z). Every field is
     * nullable and omitted when absent (NON_NULL):
     * <ul>
     *   <li>{@code tierId} — a REAL tier of this event, fuzzy-matched from the model's tier
     *       reference; null (never invented) when no tier matched.</li>
     *   <li>{@code suggestedPriceMinor} / {@code suggestedDateIso} — the concrete numeric
     *       suggestion the guardrail validator bounded (price 0.5×–2× current, date in future).</li>
     *   <li>{@code momentumSuggestionId} — set for {@code campaign} actions when the marketing
     *       momentum engine has a LIVE suggestion for this event, so the FE deep-links into the
     *       exact momentum card; null → the FE falls back to the momentum surface generally.</li>
     * </ul>
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ActionTarget(java.util.UUID tierId, Integer suggestedPriceMinor,
                               String suggestedDateIso, java.util.UUID momentumSuggestionId) {}

    /**
     * What the estimate leaned on — cluster size, relaxation applied, filters, aggregates.
     *
     * <p>FE-alignment (2026-07-22, frozen): {@code filters} is a human-readable, LANGUAGE-
     * NEUTRAL display string interpolated directly into localized copy ("based on N events in
     * …") — e.g. {@code "techno · 101–300 cap · summer · Amsterdam"} — built relaxation-aware
     * (country instead of city once geography widened; season omitted once dropped).
     * {@code aggregates} is a FLAT map with display-ready keys, rendered generically by the FE
     * in benchmark-only mode; foreign-derived entries appear ONLY when the ≥5 privacy cluster
     * floor was met (§6.4), and figures are the already-rounded privacy-preserved values.
     */
    public record Comparables(int clusterSize, int ownCount, String relaxation,
                              String filters, java.util.Map<String, Object> aggregates) {}
}
