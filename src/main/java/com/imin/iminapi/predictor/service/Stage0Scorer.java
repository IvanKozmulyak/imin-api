package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.model.LanguageTier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stage 0 — the LLM-reasoned scorer (spec §7.1/§7.2, task 86cav474p). ONE JSON-schema-
 * constrained call per score (Spring AI {@code .entity()} → BeanOutputConverter, same
 * precedent as {@code ConceptSetService}), low temperature, behind the platform's @Primary
 * OpenRouter {@link ChatClient}.
 *
 * <p>The scorer returns the raw {@link Stage0Output}; assembling it into a
 * {@link PredictionResult} — stamping tier/surface/comparables — and validating it are the
 * pipeline's job. The scorer NEVER decides the language tier; it only words for the tier it
 * is told (§5: wording is earned by data density, not chosen by the model).
 */
@Service
public class Stage0Scorer {

    /**
     * Prompt semver — stamped on every ledger row. A change to ANY prompt text below
     * (including the schema hints inside {@link Stage0Output}) is a VERSION BUMP, or eval
     * comparability across ledger rows dies silently (spec §7.3). Patch = wording tweak,
     * minor = new instruction/field, major = restructure.
     */
    public static final String PROMPT_VERSION = "1.1.0";

    /** Low temperature: numeric JSON stability over creativity (spec §7.2). */
    private static final double TEMPERATURE = 0.2;

    /**
     * The LLM-facing output shape. Kept separate from {@link PredictionResult} so the model
     * only ever sees/produces the estimate fields — never tier, provenance or metadata. Its
     * {@link RecCandidate} recommendations are RAW: the {@code RecommendationEngine} resolves
     * their tier reference to a real {@code tierId} and folds price/date into a structured
     * {@link PredictionResult.ActionTarget} before render.
     */
    public record Stage0Output(
            PredictionResult.Band selloutBand,
            PredictionResult.Range attendanceRange,
            PredictionResult.LongRange revenueRangeMinor,
            List<PredictionResult.Factor> factors,
            List<RecCandidate> recommendations) {}

    /**
     * A raw recommendation as the model emits it (task 86cav479w/86cav479z). {@code impact}:
     * HIGH | MED (the model ranks by expected impact and the list must be impact-descending).
     * {@code actionType}: the closed set {campaign, tier_edit, tier_add, tier_transition,
     * promo_create, capacity, announce}. {@code tierRef} is a free-text reference to the tier
     * the recommendation concerns (e.g. "Early Bird") — the engine fuzzy-matches it to a real
     * tier id. {@code priceMinor}/{@code dateIso} are the concrete numeric suggestion the
     * guardrail validator bounds and the engine folds into the structured target.
     */
    public record RecCandidate(String id, String claim, String evidence, String impact,
                               String actionType, String tierRef,
                               Integer priceMinor, String dateIso) {}

    private final ChatClient chat;
    private final PredictorProperties props;
    private final String platformModel;

    public Stage0Scorer(ChatClient chat, PredictorProperties props,
                        @Value("${openrouter.model}") String platformModel) {
        this.chat = chat;
        this.props = props;
        this.platformModel = platformModel;
    }

    /** The model id used for scoring — config override or the platform default. Ledger-stamped. */
    public String modelId() {
        String m = props.getModel();
        return (m == null || m.isBlank()) ? platformModel : m;
    }

    /**
     * One scoring call. {@code validatorErrors} non-empty = this is the single guardrail
     * retry (§7.2): the previous output's deterministic validation failures are appended so
     * the model can correct them. Throws on transport/parse failure — the pipeline owns
     * retry/fallback policy.
     */
    public Stage0Output score(PredictionInputSnapshot snap, LanguageTier tier, List<String> validatorErrors) {
        Stage0Output out = chat.prompt()
                .options(OpenAiChatOptions.builder().model(modelId()).temperature(TEMPERATURE).build())
                .user(buildPrompt(snap, tier, validatorErrors))
                .call()
                .entity(Stage0Output.class);
        if (out == null) throw new IllegalStateException("Stage 0 scorer returned no parseable output");
        return out;
    }

    // ---- prompt -----------------------------------------------------------------

    private String buildPrompt(PredictionInputSnapshot snap, LanguageTier tier, List<String> validatorErrors) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are the scoring engine of an event-ticketing platform's success predictor.
                An organizer is drafting an event; estimate how it will sell BEFORE they commit
                capacity, pricing and date. You advise, you never promise.

                HARD RULES (violations are rejected by a deterministic validator):
                - RANGES ONLY, never point estimates. Probabilities are bands (lowPct-highPct),
                  attendance and revenue are ranges. No "will", no "guaranteed", no "will sell out".
                - The WIDTH of every band must reflect the evidence: thin comparable support means
                  WIDE bands. Do not manufacture confidence.
                - attendanceRange must fit within [0, capacity]. revenueRangeMinor must be coherent
                  with attendanceRange multiplied by the tier prices (minor units).
                - The sell-out band must cohere with attendance-vs-capacity: do not claim high
                  sell-out probability while predicting attendance far below capacity, and do not
                  claim near-zero sell-out probability while predicting attendance at capacity.
                - NEVER invent comparable events or statistics. The comparables below are the ONLY
                  outcome data that exists. Everything beyond them is market prior — say so in the
                  factor's evidence.
                - 3 to 5 assessment factors, each tagged direction "supporting" or "opposing", each
                  with a concrete evidence sentence naming what earned it (a comparable statistic,
                  a calendar fact, a pricing fact — never vibes).
                - 0 to 3 setup recommendations, ONLY where the draft deviates materially from what
                  the evidence supports. No evidence, no recommendation — an empty list is a valid
                  answer. RANK them by expected impact and ORDER the list impact-descending: all
                  HIGH-impact recommendations before any MED-impact one. Each has:
                    id (short stable slug),
                    claim, evidence,
                    impact (exactly "HIGH" or "MED"),
                    actionType (one of: campaign | tier_edit | tier_add | tier_transition |
                      promo_create | capacity | announce),
                    tierRef (the NAME of the specific tier this concerns, e.g. "Early Bird", or
                      omit when it targets no single tier),
                    priceMinor (a suggested price in minor units — set ONLY for tier_edit/tier_add
                      price moves; must stay within 0.5x-2x of current tier prices),
                    dateIso (a suggested future date, ISO-8601 — set ONLY for tier_transition /
                      date moves).
                  Use actionType "campaign" for "send/announce to your audience" opportunities —
                  the platform routes these into its existing campaign momentum surface.
                """);

        sb.append("\nLANGUAGE TIER (earned by data density, not negotiable): ").append(tier.name()).append('\n');
        switch (tier) {
            case C -> sb.append("""
                    Tier C = "assessment": QUALITATIVE wording, WIDE bands. This estimate rests on
                    market priors, not yet on platform outcome data — factor evidence must be honest
                    about that. Still no point estimates.
                    """);
            case B -> sb.append("Tier B = \"estimate\": moderate confidence wording, honest bands.\n");
            case A -> sb.append("Tier A = \"forecast\": the segment has real outcome depth, including this organizer's own events. Bands may be tighter where the comparables genuinely agree.\n");
        }

        sb.append("\n=== WHAT IS KNOWN (the full scoring input) ===\n");
        sb.append(snap.canonicalJson()).append('\n');

        sb.append("\n=== WHAT IS UNKNOWN (do not guess these; they are not in the data) ===\n");
        sb.append("- venue type and indoor/open-air setting (not captured by the platform)\n");
        sb.append("- attendee satisfaction / NPS history (survey does not exist yet)\n");
        sb.append("- whether the concept/poster were AI-generated (provenance mostly unrecorded)\n");
        for (String u : unknownSnapshotFields(snap)) sb.append("- ").append(u).append('\n');

        sb.append("\n=== COMPARABLE SUPPORT ===\n");
        PredictionInputSnapshot.CorpusLine c = snap.comparables();
        sb.append("Density: ").append(c.densityTotal()).append(" comparable completed events (")
                .append(c.ownCount()).append(" this organizer's own, ").append(c.foreignCount())
                .append(" foreign), retrieved with relaxation ").append(c.relaxation()).append(".\n");
        if (c.foreignAggregate() == null && c.foreignCount() > 0) {
            sb.append("Foreign cluster below the privacy floor - foreign events are NOT usable individually and no foreign aggregate exists.\n");
        }
        sb.append("Capacity bound: ").append(snap.capacity()).append(". Tier price bounds (minor units) are in the tiers array above.\n");

        if (validatorErrors != null && !validatorErrors.isEmpty()) {
            sb.append("\n=== VALIDATOR REJECTED YOUR PREVIOUS ANSWER — FIX EXACTLY THESE ===\n");
            for (String err : validatorErrors) sb.append("- ").append(err).append('\n');
        }

        sb.append("\nReturn ONLY the JSON object matching the requested schema.\n");
        return sb.toString();
    }

    /** Snapshot fields that are null for THIS draft — enumerated so the model can't assume them. */
    private static List<String> unknownSnapshotFields(PredictionInputSnapshot snap) {
        List<String> out = new java.util.ArrayList<>();
        if (snap.city() == null) out.add("city (draft has no venue city yet)");
        if (snap.country() == null) out.add("country (draft has no venue country yet)");
        if (snap.genreFamily() == null) out.add("genre (draft has no genre yet)");
        if (snap.eventDateIso() == null) out.add("event date (draft has no date yet)");
        if (snap.capacity() == null || snap.capacity() == 0) out.add("capacity (draft has no ticket tiers yet)");
        if (!snap.holidayTableCovers()) out.add("public-holiday calendar (no static-table coverage for this market/date — holiday effects are unknown, not absent)");
        if (snap.organizerTenureDays() == null) out.add("organizer tenure");
        return out;
    }
}
