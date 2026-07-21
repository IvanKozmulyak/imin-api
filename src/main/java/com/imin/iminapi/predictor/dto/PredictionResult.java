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
 */
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

    /** Probability band in whole percent, e.g. 55–75. */
    public record Band(int lowPct, int highPct) {}

    /** Integer range (attendance). */
    public record Range(int low, int high) {}

    /** Long range (revenue in minor units). */
    public record LongRange(long low, long high) {}

    /** One assessment factor. direction: supporting | opposing. Evidence is mandatory (§5). */
    public record Factor(String text, String direction, String evidence) {}

    /**
     * One setup recommendation. {@code actionType}: adjust_price | adjust_capacity |
     * change_date | add_tier | adjust_tiers | add_promo | other. {@code actionTarget} is the
     * surface hint the FE deep-links with (e.g. "tiers", "schedule"). {@code priceMinor} /
     * {@code dateIso} carry the concrete suggestion when the recommendation is numeric so the
     * validator can bound it.
     */
    public record Recommendation(String id, String claim, String evidence,
                                 String actionType, String actionTarget,
                                 Integer priceMinor, String dateIso) {}

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
