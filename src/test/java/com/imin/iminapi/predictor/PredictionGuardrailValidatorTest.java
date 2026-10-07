package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.service.PredictionGuardrailValidator;
import com.imin.iminapi.predictor.service.Stage0Scorer;
import com.imin.iminapi.predictor.service.Stage0Scorer.RecCandidate;
import com.imin.iminapi.predictor.service.Stage0Scorer.Stage0Output;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial fixtures for the guardrail validator: each is an output shape a hallucinating model
 * could plausibly emit, and the deterministic validator must reject every one.
 */
class PredictionGuardrailValidatorTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private final PredictionGuardrailValidator sut = new PredictionGuardrailValidator();

    /** capacity 250, tier prices 1500–2400 minor. */
    private static PredictionGuardrailValidator.Context ctx() {
        return new PredictionGuardrailValidator.Context(250, 1500, 2400, NOW);
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
    private static Stage0Output valid() {
        return new Stage0Output(
                new Stage0Scorer.RawBand(35, 60),
                new Stage0Scorer.RawRange(120, 210),
                new Stage0Scorer.RawLongRange(120 * 1500L, 210 * 2400L),
                goodFactors(),
                List.of());
    }

    private static Stage0Output withRecs(RecCandidate... recs) {
        return new Stage0Output(new Stage0Scorer.RawBand(35, 60),
                new Stage0Scorer.RawRange(120, 210), null, goodFactors(), List.of(recs));
    }

    private static Stage0Output withFactors(PredictionResult.Factor first) {
        return new Stage0Output(new Stage0Scorer.RawBand(35, 60), new Stage0Scorer.RawRange(120, 210), null,
                List.of(first, factor("Saturday date", "supporting", "comparable stat"),
                        factor("New organizer", "opposing", "no completed events")), List.of());
    }

    private static Predicate<String> has(String text) {
        return e -> e.contains(text);
    }

    private static Predicate<String> rule(String id) {
        return e -> e.startsWith(id);
    }

    /**
     * A raw carrier with a missing number used to bind to 0, so an empty estimate validated and was
     * served as a real 0–0 forecast; the absence must route through the retry to benchmark-only.
     */
    @Test
    void emptyEstimateObjectsAreRejectedNotReadAsZero() {
        Stage0Output out = new Stage0Output(
                new Stage0Scorer.RawBand(null, null),
                new Stage0Scorer.RawRange(null, null),
                null, goodFactors(), List.of());

        assertThat(sut.validate(out, ctx()))
                .anyMatch(e -> e.contains("selloutBand.lowPct is required"))
                .anyMatch(e -> e.contains("selloutBand.highPct is required"))
                .anyMatch(e -> e.contains("attendanceRange.low is required"))
                .anyMatch(e -> e.contains("attendanceRange.high is required"));
    }

    @Test
    void partialRevenueRangeIsRejectedThoughAnAbsentOneIsFine() {
        Stage0Output partial = new Stage0Output(
                new Stage0Scorer.RawBand(35, 60), new Stage0Scorer.RawRange(120, 210),
                new Stage0Scorer.RawLongRange(180_000L, null), goodFactors(), List.of());
        assertThat(sut.validate(partial, ctx())).anyMatch(e -> e.contains("revenueRangeMinor is present but incomplete"));

        Stage0Output absent = new Stage0Output(
                new Stage0Scorer.RawBand(35, 60), new Stage0Scorer.RawRange(120, 210),
                null, goodFactors(), List.of());
        assertThat(sut.validate(absent, ctx())).isEmpty();
    }

    @Test
    void coherentOutputPasses() {
        assertThat(sut.validate(valid(), ctx())).isEmpty();
    }

    static Stream<Arguments> rejectedOutputs() {
        RecCandidate r = new RecCandidate("r", "claim", "evidence", "MED", "campaign", null, null, null);
        return Stream.of(
                // the id is stored in a VARCHAR(128) on dismissal, so a sentence-length id could only 400
                Arguments.of("overLongRecommendationId", withRecs(new RecCandidate("z".repeat(129), "Lower Early Bird",
                        "priced above the comparable band", "HIGH", "tier_edit", null, null, null)), ctx(),
                        has("short stable slug")),
                Arguments.of("oneMissingNumberInsideAPresentObject", new Stage0Output(new Stage0Scorer.RawBand(35, 60),
                        new Stage0Scorer.RawRange(120, null), null, goodFactors(), List.of()), ctx(),
                        has("attendanceRange.high is required")),
                // an omitted estimate would be served as `ready`, claiming an assessment the model never made
                Arguments.of("missingSelloutBand", new Stage0Output(null, new Stage0Scorer.RawRange(120, 210),
                        null, goodFactors(), List.of()), ctx(), has("selloutBand is required")),
                Arguments.of("missingAttendanceRangeWhenTheDraftHasCapacity", new Stage0Output(
                        new Stage0Scorer.RawBand(80, 95), null, null, goodFactors(), List.of()), ctx(),
                        has("attendanceRange is required")),
                Arguments.of("overCapacityAttendance", new Stage0Output(new Stage0Scorer.RawBand(35, 60),
                        new Stage0Scorer.RawRange(120, 400), null, goodFactors(), List.of()), ctx(),
                        has("exceeds capacity")),
                Arguments.of("negativeAttendance", new Stage0Output(null, new Stage0Scorer.RawRange(-5, 100), null,
                        goodFactors(), List.of()), ctx(), has(">= 0")),
                // at most 120/250 attendees yet a sell-out up to 90% likely
                Arguments.of("selloutBandIncoherentWithLowAttendance", new Stage0Output(new Stage0Scorer.RawBand(60, 90),
                        new Stage0Scorer.RawRange(80, 120), null, goodFactors(), List.of()), ctx(), rule("S1")),
                // the room fills (240-250/250) yet sell-out at most 5% likely
                Arguments.of("nearZeroSelloutWhileAttendanceAtCapacity", new Stage0Output(new Stage0Scorer.RawBand(0, 5),
                        new Stage0Scorer.RawRange(240, 250), null, goodFactors(), List.of()), ctx(), rule("S2")),
                // 10x what the dearest tier times the highest attendance could gross
                Arguments.of("revenueIncoherentWithAttendanceTimesPrices", new Stage0Output(
                        new Stage0Scorer.RawBand(35, 60), new Stage0Scorer.RawRange(120, 210),
                        new Stage0Scorer.RawLongRange(120 * 1500L, 10 * 210 * 2400L), goodFactors(), List.of()),
                        ctx(), rule("R2")),
                Arguments.of("recommendedPriceTenTimesTierPrice", withRecs(new RecCandidate("raise-door",
                        "Raise the door price", "comparable events priced higher", "HIGH", "tier_edit", "Door", 24_000,
                        null)), ctx(), rule("P1")),
                Arguments.of("recommendedDateInThePast", withRecs(new RecCandidate("move-date", "Move to a Saturday",
                        "Saturdays outperform in the cluster", "HIGH", "tier_transition", "Early Bird", null,
                        "2026-05-01T20:00:00Z")), ctx(), rule("P2")),
                Arguments.of("invalidImpactTag", withRecs(new RecCandidate("r1", "Add a VIP tier",
                        "comparable events had VIP", "MASSIVE", "tier_add", null, null, null)), ctx(),
                        has("impact must be one of")),
                Arguments.of("nonDescendingImpactOrder", withRecs(
                        new RecCandidate("a-med", "Lower Early Bird", "priced above band", "MED", "tier_edit",
                                "Early Bird", 1400, null),
                        new RecCandidate("b-high", "Send a reminder", "audience is warm", "HIGH", "campaign", null,
                                null, null)), ctx(), has("impact-descending")),
                Arguments.of("unknownActionType", withRecs(new RecCandidate("r1", "Do a thing", "evidence", "HIGH",
                        "adjust_price", null, null, null)), ctx(), has("actionType must be one of")),
                Arguments.of("factorWithEmptyEvidence", withFactors(factor("Great vibe expected", "supporting", "")),
                        ctx(), has("evidence is empty")),
                Arguments.of("tooFewFactors", new Stage0Output(new Stage0Scorer.RawBand(35, 60),
                        new Stage0Scorer.RawRange(120, 210), null,
                        List.of(factor("Only one", "supporting", "something")), List.of()), ctx(), has("3-5")),
                Arguments.of("bannedPhraseWillSellOut", withFactors(factor("This event will sell out fast",
                        "supporting", "strong comparables")), ctx(), rule("W1")),
                Arguments.of("barePointEstimate", withFactors(factor("Demand outlook", "supporting",
                        "the event will sell 240 tickets based on comparables")), ctx(), rule("W1")),
                Arguments.of("fourthRecommendation", withRecs(withId(r, "a"), withId(r, "b"), withId(r, "c"),
                        withId(r, "d")), ctx(), has("at most 3")),
                Arguments.of("revenueWithoutTiers", new Stage0Output(null, null,
                        new Stage0Scorer.RawLongRange(0L, 100_000L), goodFactors(), List.of()),
                        new PredictionGuardrailValidator.Context(0, null, null, NOW), has("no ticket tiers")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedOutputs")
    void rejected(String name, Stage0Output out, PredictionGuardrailValidator.Context ctx, Predicate<String> error) {
        assertThat(sut.validate(out, ctx)).anyMatch(error);
    }

    private static RecCandidate withId(RecCandidate r, String id) {
        return new RecCandidate(id, r.claim(), r.evidence(), r.impact(), r.actionType(),
                r.tierRef(), r.priceMinor(), r.dateIso());
    }
}
