package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.service.Stage0Scorer.Stage0Output;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The guardrail validator (spec §7.2, task 86cav475g) — deterministic, post-LLM,
 * <b>NEVER CUTTABLE</b> (one of the four items that ARE the §3 decision).
 * UNVALIDATED NUMBERS NEVER REACH A SCREEN: the pipeline may render an LLM output only after
 * this returns no errors; one retry with the errors fed back, then benchmark-only.
 *
 * <p>Every rule is numbered and numeric where it bounds numbers; the constants are the
 * rule definitions, kept here (not config) so a "tuning" change is a reviewed code change.
 */
@Component
public class PredictionGuardrailValidator {

    /** Everything the rules need from outside the LLM output. All deterministic. */
    public record Context(
            int capacity,           // sum of tier quantities (0 = no tiers yet)
            Integer minTierPriceMinor,  // null when no tiers
            Integer maxTierPriceMinor,  // null when no tiers
            Instant now) {}

    // ---- Rule constants (the numeric definitions referenced below) -------------

    /**
     * S1: if attendanceRange.high < {@code S1_ATT_FRACTION} × capacity, then
     * selloutBand.highPct must be ≤ {@code S1_MAX_HIGH_PCT} — you cannot call a sell-out
     * likely while predicting attendance well short of capacity.
     */
    static final double S1_ATT_FRACTION = 0.80;
    static final int S1_MAX_HIGH_PCT = 40;

    /**
     * S2: if attendanceRange.high ≥ {@code S2_ATT_FRACTION} × capacity, then
     * selloutBand.highPct must be ≥ {@code S2_MIN_HIGH_PCT} — if the room may fill, the
     * sell-out chance cannot be called near-zero.
     */
    static final double S2_ATT_FRACTION = 0.95;
    static final int S2_MIN_HIGH_PCT = 20;

    /**
     * S3: if attendanceRange.low ≥ {@code S3_ATT_FRACTION} × capacity (even the pessimistic
     * end fills the room), selloutBand.lowPct must be ≥ {@code S3_MIN_LOW_PCT}.
     */
    static final double S3_ATT_FRACTION = 0.95;
    static final int S3_MIN_LOW_PCT = 40;

    /**
     * R1/R2 revenue coherence tolerance: revenueRange.low may undercut
     * minPrice×attendance.low by up to this fraction (promo discounts), and
     * revenueRange.high may exceed maxPrice×attendance.high by up to this fraction
     * (rounding). Beyond that the arithmetic is incoherent.
     */
    static final double REVENUE_TOLERANCE = 0.10;

    /** P1: recommended prices must stay within [0.5×minTierPrice, 2×maxTierPrice]. */
    static final double PRICE_MIN_FACTOR = 0.5;
    static final double PRICE_MAX_FACTOR = 2.0;

    static final int FACTORS_MIN = 3;
    static final int FACTORS_MAX = 5;
    static final int RECOMMENDATIONS_MAX = 3;

    private static final Set<String> DIRECTIONS = Set.of("supporting", "opposing");
    private static final Set<String> ACTION_TYPES = Set.of(
            "adjust_price", "adjust_capacity", "change_date", "add_tier", "adjust_tiers",
            "add_promo", "other");

    /**
     * The §5 banned-phrase list: certainty language a range-based product must never emit,
     * plus point-estimate phrasings ("will sell 240 tickets", "exactly 300 attendees").
     * Checked case-insensitively across ALL free text (factor text/evidence, recommendation
     * claim/evidence).
     */
    private static final List<Pattern> BANNED = List.of(
            Pattern.compile("\\bguaranteed?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwill\\s+sell\\s+out\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwill\\s+definitely\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcertain\\s+to\\s+sell\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bno\\s+risk\\b", Pattern.CASE_INSENSITIVE),
            // bare point estimates: "will sell 240", "exactly 300 tickets/attendees/people"
            Pattern.compile("\\bwill\\s+sell\\s+\\d", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bexactly\\s+\\d+\\s+(tickets|attendees|people)\\b", Pattern.CASE_INSENSITIVE));

    /**
     * Validate one LLM output against its deterministic context. Empty list = pass.
     * Error strings are written to be FED BACK to the model on the single retry, so each
     * names the violated bound concretely.
     */
    public List<String> validate(Stage0Output out, Context ctx) {
        List<String> errors = new ArrayList<>();
        if (out == null) {
            errors.add("no parseable output");
            return errors;
        }

        // ---- F: factors 3–5, direction tagged, evidence mandatory (§5 evidence-or-silence)
        List<PredictionResult.Factor> factors = out.factors();
        if (factors == null || factors.size() < FACTORS_MIN || factors.size() > FACTORS_MAX) {
            errors.add("factors must contain " + FACTORS_MIN + "-" + FACTORS_MAX + " entries, got "
                    + (factors == null ? 0 : factors.size()));
        } else {
            for (int i = 0; i < factors.size(); i++) {
                PredictionResult.Factor f = factors.get(i);
                if (f == null || isBlank(f.text())) errors.add("factors[" + i + "].text is empty");
                if (f == null || isBlank(f.evidence())) errors.add("factors[" + i + "].evidence is empty - every factor must name what earned it");
                if (f != null && (f.direction() == null || !DIRECTIONS.contains(f.direction().toLowerCase(Locale.ROOT)))) {
                    errors.add("factors[" + i + "].direction must be 'supporting' or 'opposing'");
                }
            }
        }

        // ---- A: attendance range bounds
        PredictionResult.Range att = out.attendanceRange();
        if (att != null) {
            if (att.low() < 0) errors.add("attendanceRange.low must be >= 0, got " + att.low());
            if (att.high() < att.low()) errors.add("attendanceRange.high (" + att.high() + ") < low (" + att.low() + ")");
            if (att.high() > ctx.capacity()) {
                errors.add("attendanceRange.high (" + att.high() + ") exceeds capacity (" + ctx.capacity() + ")");
            }
        }

        // ---- B: sell-out band bounds + S1-S3 coherence with attendance-vs-capacity
        PredictionResult.Band band = out.selloutBand();
        if (band != null) {
            if (band.lowPct() < 0 || band.highPct() > 100 || band.lowPct() > band.highPct()) {
                errors.add("selloutBand must satisfy 0 <= lowPct <= highPct <= 100, got "
                        + band.lowPct() + "-" + band.highPct());
            }
            if (att != null && ctx.capacity() > 0) {
                if (att.high() < S1_ATT_FRACTION * ctx.capacity() && band.highPct() > S1_MAX_HIGH_PCT) {
                    errors.add("S1 incoherent: attendanceRange.high (" + att.high() + ") is under "
                            + (int) (S1_ATT_FRACTION * 100) + "% of capacity (" + ctx.capacity()
                            + ") but selloutBand.highPct (" + band.highPct() + ") > " + S1_MAX_HIGH_PCT);
                }
                if (att.high() >= S2_ATT_FRACTION * ctx.capacity() && band.highPct() < S2_MIN_HIGH_PCT) {
                    errors.add("S2 incoherent: attendanceRange.high (" + att.high() + ") is at/near capacity ("
                            + ctx.capacity() + ") but selloutBand.highPct (" + band.highPct() + ") < " + S2_MIN_HIGH_PCT);
                }
                if (att.low() >= S3_ATT_FRACTION * ctx.capacity() && band.lowPct() < S3_MIN_LOW_PCT) {
                    errors.add("S3 incoherent: even attendanceRange.low (" + att.low() + ") fills capacity ("
                            + ctx.capacity() + ") but selloutBand.lowPct (" + band.lowPct() + ") < " + S3_MIN_LOW_PCT);
                }
            }
        }

        // ---- R: revenue coherence with attendance × tier price bounds (±10% tolerance)
        PredictionResult.LongRange rev = out.revenueRangeMinor();
        if (rev != null) {
            if (rev.low() < 0) errors.add("revenueRangeMinor.low must be >= 0, got " + rev.low());
            if (rev.high() < rev.low()) errors.add("revenueRangeMinor.high (" + rev.high() + ") < low (" + rev.low() + ")");
            if (ctx.minTierPriceMinor() == null || ctx.maxTierPriceMinor() == null) {
                if (rev.high() > 0) errors.add("revenueRangeMinor present but the draft has no ticket tiers - drop it");
            } else if (att != null) {
                long floor = (long) Math.floor(ctx.minTierPriceMinor() * (long) att.low() * (1.0 - REVENUE_TOLERANCE));
                long ceil = (long) Math.ceil(ctx.maxTierPriceMinor() * (long) att.high() * (1.0 + REVENUE_TOLERANCE));
                if (rev.low() < floor) {
                    errors.add("R1 incoherent: revenueRangeMinor.low (" + rev.low() + ") is below minTierPrice x attendanceRange.low with tolerance (" + floor + ")");
                }
                if (rev.high() > ceil) {
                    errors.add("R2 incoherent: revenueRangeMinor.high (" + rev.high() + ") exceeds maxTierPrice x attendanceRange.high with tolerance (" + ceil + ")");
                }
            }
        }

        // ---- P: recommendations ≤3, complete, priced within 0.5x-2x, dated in the future
        List<PredictionResult.Recommendation> recs = out.recommendations() == null ? List.of() : out.recommendations();
        if (recs.size() > RECOMMENDATIONS_MAX) {
            errors.add("recommendations must contain at most " + RECOMMENDATIONS_MAX + ", got " + recs.size());
        }
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < recs.size(); i++) {
            PredictionResult.Recommendation r = recs.get(i);
            if (r == null) { errors.add("recommendations[" + i + "] is null"); continue; }
            if (isBlank(r.id())) errors.add("recommendations[" + i + "].id is empty");
            else if (!seenIds.add(r.id())) errors.add("recommendations[" + i + "].id duplicates another recommendation");
            if (isBlank(r.claim())) errors.add("recommendations[" + i + "].claim is empty");
            if (isBlank(r.evidence())) errors.add("recommendations[" + i + "].evidence is empty - no evidence, no recommendation");
            if (r.actionType() == null || !ACTION_TYPES.contains(r.actionType().toLowerCase(Locale.ROOT))) {
                errors.add("recommendations[" + i + "].actionType must be one of " + ACTION_TYPES);
            }
            if (r.priceMinor() != null) {
                if (ctx.minTierPriceMinor() == null || ctx.maxTierPriceMinor() == null) {
                    errors.add("recommendations[" + i + "] suggests a price but the draft has no tiers to anchor it");
                } else {
                    long min = (long) Math.floor(ctx.minTierPriceMinor() * PRICE_MIN_FACTOR);
                    long max = (long) Math.ceil(ctx.maxTierPriceMinor() * PRICE_MAX_FACTOR);
                    if (r.priceMinor() < min || r.priceMinor() > max) {
                        errors.add("P1: recommendations[" + i + "].priceMinor (" + r.priceMinor()
                                + ") outside [0.5x min tier price, 2x max tier price] = [" + min + ", " + max + "]");
                    }
                }
            }
            if (r.dateIso() != null) {
                try {
                    Instant d = Instant.parse(r.dateIso());
                    if (!d.isAfter(ctx.now())) {
                        errors.add("P2: recommendations[" + i + "].dateIso (" + r.dateIso() + ") is not in the future");
                    }
                } catch (DateTimeParseException e) {
                    errors.add("recommendations[" + i + "].dateIso is not ISO-8601: " + r.dateIso());
                }
            }
        }

        // ---- W: banned-phrase sweep over every free-text field (§5 wording rules)
        if (factors != null) {
            for (int i = 0; i < factors.size(); i++) {
                PredictionResult.Factor f = factors.get(i);
                if (f == null) continue;
                bannedSweep(errors, "factors[" + i + "].text", f.text());
                bannedSweep(errors, "factors[" + i + "].evidence", f.evidence());
            }
        }
        for (int i = 0; i < recs.size(); i++) {
            PredictionResult.Recommendation r = recs.get(i);
            if (r == null) continue;
            bannedSweep(errors, "recommendations[" + i + "].claim", r.claim());
            bannedSweep(errors, "recommendations[" + i + "].evidence", r.evidence());
        }

        return errors;
    }

    private static void bannedSweep(List<String> errors, String field, String text) {
        if (text == null) return;
        for (Pattern p : BANNED) {
            if (p.matcher(text).find()) {
                errors.add("W1 banned phrase in " + field + ": matches '" + p.pattern()
                        + "' - certainty language and point estimates are forbidden; use ranges and hedged wording");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
