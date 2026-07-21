package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.service.PredictionGuardrailValidator;
import com.imin.iminapi.predictor.service.Stage0Scorer.Stage0Output;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial fixtures for the guardrail validator (task 86cav475g) — each fixture is an
 * output shape a HALLUCINATING model could plausibly emit; the deterministic validator must
 * reject every one. Unvalidated numbers never reach a screen.
 */
class PredictionGuardrailValidatorTest {

    private final PredictionGuardrailValidator sut = new PredictionGuardrailValidator();
    private final Instant now = Instant.parse("2026-06-01T12:00:00Z");

    /** capacity 250, tier prices 1500–2400 minor. */
    private PredictionGuardrailValidator.Context ctx() {
        return new PredictionGuardrailValidator.Context(250, 1500, 2400, now);
    }

    private static PredictionResult.Factor factor(String text, String direction, String evidence) {
        return new PredictionResult.Factor(text, direction, evidence);
    }

    private static List<PredictionResult.Factor> goodFactors() {
        return List.of(
                factor("Saturday date in a strong month", "supporting", "6 of 9 comparable NL techno events on Saturdays reached 80%+ of capacity"),
                factor("Early Bird priced inside the comparable band", "supporting", "comparable avg ticket sits between the draft's tier prices"),
                factor("First event for this organizer in this city", "opposing", "organizer has no completed events in the comparable cluster"));
    }

    /** A coherent, honest output — must pass. */
    private Stage0Output valid() {
        return new Stage0Output(
                new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210),
                new PredictionResult.LongRange(120 * 1500L, 210 * 2400L),
                goodFactors(),
                List.of());
    }

    @Test
    void coherentOutputPasses() {
        assertThat(sut.validate(valid(), ctx())).isEmpty();
    }

    // ---- adversarial fixtures ----------------------------------------------------

    @Test
    void overCapacityAttendanceRejected() {
        Stage0Output out = new Stage0Output(
                new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 400), // capacity is 250
                null, goodFactors(), List.of());
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.contains("exceeds capacity"));
    }

    @Test
    void negativeAttendanceRejected() {
        Stage0Output out = new Stage0Output(null,
                new PredictionResult.Range(-5, 100), null, goodFactors(), List.of());
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.contains(">= 0"));
    }

    @Test
    void selloutBandIncoherentWithLowAttendanceRejected() {
        // Predicts at most 120/250 attendees yet calls a sell-out up to 90% likely (rule S1).
        Stage0Output out = new Stage0Output(
                new PredictionResult.Band(60, 90),
                new PredictionResult.Range(80, 120),
                null, goodFactors(), List.of());
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.startsWith("S1"));
    }

    @Test
    void nearZeroSelloutWhileAttendanceAtCapacityRejected() {
        // Predicts the room fills (240-250/250) yet calls sell-out at most 5% likely (rule S2).
        Stage0Output out = new Stage0Output(
                new PredictionResult.Band(0, 5),
                new PredictionResult.Range(240, 250),
                null, goodFactors(), List.of());
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.startsWith("S2"));
    }

    @Test
    void revenueIncoherentWithAttendanceTimesPricesRejected() {
        // 10x what the dearest tier times the highest attendance could gross (rule R2).
        Stage0Output out = new Stage0Output(
                new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210),
                new PredictionResult.LongRange(120 * 1500L, 10 * 210 * 2400L),
                goodFactors(), List.of());
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.startsWith("R2"));
    }

    @Test
    void recommendedPriceTenTimesTierPriceRejected() {
        PredictionResult.Recommendation rec = new PredictionResult.Recommendation(
                "raise-door", "Raise the door price", "comparable events priced higher",
                "adjust_price", "tiers", 24_000, null); // 10x the 2400 max tier
        Stage0Output out = new Stage0Output(new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210), null, goodFactors(), List.of(rec));
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.startsWith("P1"));
    }

    @Test
    void recommendedDateInThePastRejected() {
        PredictionResult.Recommendation rec = new PredictionResult.Recommendation(
                "move-date", "Move to a Saturday", "Saturdays outperform in the cluster",
                "change_date", "schedule", null, "2026-05-01T20:00:00Z"); // before now
        Stage0Output out = new Stage0Output(new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210), null, goodFactors(), List.of(rec));
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.startsWith("P2"));
    }

    @Test
    void factorWithEmptyEvidenceRejected() {
        List<PredictionResult.Factor> factors = List.of(
                factor("Great vibe expected", "supporting", ""),
                factor("Saturday date", "supporting", "comparable stat"),
                factor("New organizer", "opposing", "no completed events"));
        Stage0Output out = new Stage0Output(new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210), null, factors, List.of());
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.contains("evidence is empty"));
    }

    @Test
    void tooFewFactorsRejected() {
        Stage0Output out = new Stage0Output(new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210), null,
                List.of(factor("Only one", "supporting", "something")), List.of());
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.contains("3-5"));
    }

    @Test
    void bannedPhraseWillSellOutRejected() {
        List<PredictionResult.Factor> factors = List.of(
                factor("This event will sell out fast", "supporting", "strong comparables"),
                factor("Saturday date", "supporting", "comparable stat"),
                factor("New organizer", "opposing", "no completed events"));
        Stage0Output out = new Stage0Output(new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210), null, factors, List.of());
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.startsWith("W1"));
    }

    @Test
    void barePointEstimateRejected() {
        List<PredictionResult.Factor> factors = List.of(
                factor("Demand outlook", "supporting", "the event will sell 240 tickets based on comparables"),
                factor("Saturday date", "supporting", "comparable stat"),
                factor("New organizer", "opposing", "no completed events"));
        Stage0Output out = new Stage0Output(new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210), null, factors, List.of());
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.startsWith("W1"));
    }

    @Test
    void fourthRecommendationRejected() {
        PredictionResult.Recommendation r = new PredictionResult.Recommendation(
                "r", "claim", "evidence", "other", "other", null, null);
        Stage0Output out = new Stage0Output(new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210), null, goodFactors(),
                List.of(withId(r, "a"), withId(r, "b"), withId(r, "c"), withId(r, "d")));
        assertThat(sut.validate(out, ctx())).anyMatch(e -> e.contains("at most 3"));
    }

    @Test
    void revenueWithoutTiersRejected() {
        PredictionGuardrailValidator.Context noTiers =
                new PredictionGuardrailValidator.Context(0, null, null, now);
        Stage0Output out = new Stage0Output(null, null,
                new PredictionResult.LongRange(0, 100_000), goodFactors(), List.of());
        assertThat(sut.validate(out, noTiers)).anyMatch(e -> e.contains("no ticket tiers"));
    }

    private static PredictionResult.Recommendation withId(PredictionResult.Recommendation r, String id) {
        return new PredictionResult.Recommendation(id, r.claim(), r.evidence(), r.actionType(),
                r.actionTarget(), r.priceMinor(), r.dateIso());
    }
}
