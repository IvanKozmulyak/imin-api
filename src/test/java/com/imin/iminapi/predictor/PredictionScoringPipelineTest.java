package com.imin.iminapi.predictor;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.model.LanguageTier;
import com.imin.iminapi.predictor.model.PredictorSegmentStatus;
import com.imin.iminapi.predictor.repository.PredictorSegmentStatusRepository;
import com.imin.iminapi.predictor.service.PredictionGuardrailValidator;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot;
import com.imin.iminapi.predictor.service.PredictionInputSnapshotService;
import com.imin.iminapi.predictor.service.PredictionLedgerService;
import com.imin.iminapi.predictor.service.PredictionScoringPipeline;
import com.imin.iminapi.predictor.service.Stage0Scorer;
import com.imin.iminapi.predictor.service.Stage0Scorer.Stage0Output;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 0 pipeline behaviour (tasks 86cav474p + 86cav475g wiring): validated-or-benchmark,
 * one retry with errors fed back, kill switch, §5 tier ladder, V72 overrides — and the
 * write-before-render contract on every path.
 */
class PredictionScoringPipelineTest {

    private final Instant now = Instant.parse("2026-06-01T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private final PredictionInputSnapshotService snapshots = mock(PredictionInputSnapshotService.class);
    private final Stage0Scorer scorer = mock(Stage0Scorer.class);
    private final PredictionLedgerService ledger = mock(PredictionLedgerService.class);
    private final PredictorSegmentStatusRepository segments = mock(PredictorSegmentStatusRepository.class);
    private final PredictorProperties props = new PredictorProperties();
    // Real engine over mocked repos; validOutput carries no recommendations, so finalizeForRender
    // short-circuits without touching them.
    private final com.imin.iminapi.predictor.service.RecommendationEngine recommendations =
            new com.imin.iminapi.predictor.service.RecommendationEngine(
                    mock(com.imin.iminapi.repository.TicketTierRepository.class),
                    mock(com.imin.iminapi.marketing.repository.MomentumSuggestionRepository.class),
                    mock(com.imin.iminapi.predictor.repository.PredictionFeedbackRepository.class));

    private final PredictionScoringPipeline sut = new PredictionScoringPipeline(
            snapshots, scorer, new PredictionGuardrailValidator(), recommendations, ledger, segments, props, clock);

    private final UUID eventId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private Event event() {
        Event e = new Event();
        e.setId(eventId);
        e.setOrgId(orgId);
        return e;
    }

    /** capacity 250, tiers 1500/2400 minor, density/own configurable. */
    private PredictionInputSnapshot snap(int density, int own) {
        return new PredictionInputSnapshot(
                PredictionInputSnapshot.SNAPSHOT_VERSION, eventId,
                "Amsterdam", "NL", "techno", 250, "B101_300",
                "2026-07-18T20:00:00Z", 6, "SUMMER", 47, "EUR",
                List.of(new PredictionInputSnapshot.TierLine("Early", 1500, 50, null, null),
                        new PredictionInputSnapshot.TierLine("Door", 2400, 200, null, null)),
                List.of(), 200, 4, true, List.of(),
                new PredictionInputSnapshot.CorpusLine("NONE", density, own, density - own, List.of(), null));
    }

    /** Same snapshot (density 30 / own 5, so it earns tier A), with a display-cased genre. */
    private PredictionInputSnapshot snapWithGenre(String displayGenre) {
        PredictionInputSnapshot base = snap(30, 5);
        return new PredictionInputSnapshot(
                base.snapshotVersion(), base.eventId(), base.city(), base.country(), displayGenre,
                base.capacity(), base.capacityBand(), base.eventDateIso(), base.dayOfWeek(), base.season(),
                base.leadTimeDays(), base.currency(), base.tiers(), base.promos(),
                base.organizerTenureDays(), base.priorEventCount(), base.holidayTableCovers(),
                base.holidaysNearEvent(), base.comparables());
    }

    /** Same snapshot, but retrieved at a WIDENED rung. */
    private PredictionInputSnapshot relaxedSnap(String rung) {
        PredictionInputSnapshot base = snap(10, 1);
        return new PredictionInputSnapshot(
                base.snapshotVersion(), base.eventId(), base.city(), base.country(), base.genreFamily(),
                base.capacity(), base.capacityBand(), base.eventDateIso(), base.dayOfWeek(), base.season(),
                base.leadTimeDays(), base.currency(), base.tiers(), base.promos(),
                base.organizerTenureDays(), base.priorEventCount(), base.holidayTableCovers(),
                base.holidaysNearEvent(),
                new PredictionInputSnapshot.CorpusLine(rung, 10, 1, 9, List.of(), null));
    }

    private Stage0Output validOutput() {
        return new Stage0Output(
                new Stage0Scorer.RawBand(35, 60),
                new Stage0Scorer.RawRange(120, 210),
                new Stage0Scorer.RawLongRange(120 * 1500L, 210 * 2400L),
                List.of(new PredictionResult.Factor("Saturday in summer", "supporting", "comparable Saturdays outperform"),
                        new PredictionResult.Factor("Prices inside band", "supporting", "tier prices within comparable range"),
                        new PredictionResult.Factor("Low own history", "opposing", "organizer has few completed events")),
                List.of());
    }

    private Stage0Output invalidOutput() {
        // Over-capacity attendance — deterministic validator rejection.
        return new Stage0Output(new Stage0Scorer.RawBand(35, 60),
                new Stage0Scorer.RawRange(120, 9_999), null,
                validOutput().factors(), List.of());
    }

    private void stubLedger() {
        when(ledger.record(any())).thenReturn(UUID.randomUUID());
        when(scorer.modelId()).thenReturn("test/model");
        when(segments.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void happyPathValidatesLedgersAndReturns() {
        stubLedger();
        when(scorer.score(any(), any(), isNull())).thenReturn(validOutput());

        PredictionScoringPipeline.Scored scored = sut.score(event(), snap(10, 1));

        assertThat(scored.result().benchmarkOnly()).isFalse();
        assertThat(scored.result().confidenceTier()).isEqualTo("B"); // density 10 → tier B
        assertThat(scored.result().selloutBand()).isNotNull();
        assertThat(scored.result().promptVersion()).isEqualTo(Stage0Scorer.PROMPT_VERSION);
        assertThat(scored.ledgerId()).isNotNull();
        // write-before-render: the ledger row was recorded with the full output
        ArgumentCaptor<PredictionLedgerService.RecordCommand> cap =
                ArgumentCaptor.forClass(PredictionLedgerService.RecordCommand.class);
        verify(ledger, times(1)).record(cap.capture());
        assertThat(cap.getValue().inputSnapshotHash()).isEqualTo(scored.inputHash());
        assertThat(cap.getValue().outputJson()).contains("\"selloutBand\"");
    }

    @Test
    void invalidFirstAttemptRetriesWithErrorsThenSucceeds() {
        stubLedger();
        when(scorer.score(any(), any(), isNull())).thenReturn(invalidOutput());
        when(scorer.score(any(), any(), anyList())).thenReturn(validOutput());

        PredictionScoringPipeline.Scored scored = sut.score(event(), snap(10, 1));

        assertThat(scored.result().benchmarkOnly()).isFalse();
        // retry carried the validator errors back to the model (second call's error list)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> errs = ArgumentCaptor.forClass(List.class);
        verify(scorer, times(2)).score(any(), any(), errs.capture());
        assertThat(errs.getAllValues().get(1)).anyMatch(e -> e.contains("exceeds capacity"));
        verify(ledger, times(1)).record(any());
    }

    @Test
    void doubleValidationFailureDegradesToBenchmarkOnlyStillLedgered() {
        stubLedger();
        when(scorer.score(any(), any(), any())).thenReturn(invalidOutput());

        PredictionScoringPipeline.Scored scored = sut.score(event(), snap(10, 1));

        assertThat(scored.result().benchmarkOnly()).isTrue();
        assertThat(scored.result().selloutBand()).isNull();      // no forward numbers
        assertThat(scored.result().attendanceRange()).isNull();
        assertThat(scored.result().revenueRangeMinor()).isNull();
        assertThat(scored.result().factors()).isEmpty();
        verify(ledger, times(1)).record(any());                  // benchmark-only IS ledgered
        verify(scorer, times(2)).score(any(), any(), any());     // exactly one retry
    }

    /**
     * predictor-edge-13 end to end: an output whose estimate objects are present but whose
     * NUMBERS are missing must degrade to benchmark-only, not be served as a 0–0 forecast.
     */
    @Test
    void outputWithEmptyEstimateObjectsDegradesToBenchmarkOnly() {
        stubLedger();
        Stage0Output empty = new Stage0Output(
                new Stage0Scorer.RawBand(null, null), new Stage0Scorer.RawRange(null, null),
                null, validOutput().factors(), List.of());
        when(scorer.score(any(), any(), any())).thenReturn(empty);

        PredictionScoringPipeline.Scored scored = sut.score(event(), snap(10, 1));

        assertThat(scored.result().benchmarkOnly()).isTrue();
        assertThat(scored.result().selloutBand()).isNull();     // never a served 0-0 band
        assertThat(scored.result().attendanceRange()).isNull();
        verify(scorer, times(2)).score(any(), any(), any());    // rejected, retried, then floored
        verify(ledger, times(1)).record(any());
    }

    @Test
    void transportFailureTwiceDegradesToBenchmarkOnly() {
        stubLedger();
        when(scorer.score(any(), any(), any())).thenThrow(new IllegalStateException("no parseable output"));

        PredictionScoringPipeline.Scored scored = sut.score(event(), snap(10, 1));

        assertThat(scored.result().benchmarkOnly()).isTrue();
        verify(ledger, times(1)).record(any());
    }

    @Test
    void killSwitchSkipsLlmEntirelyButStillLedgers() {
        stubLedger();
        props.setBenchmarkOnly(true);

        PredictionScoringPipeline.Scored scored = sut.score(event(), snap(30, 5));

        assertThat(scored.result().benchmarkOnly()).isTrue();
        verify(scorer, never()).score(any(), any(), any());
        verify(ledger, times(1)).record(any());
    }

    @Test
    void tierLadderFollowsDensityThresholds() {
        assertThat(sut.earnedTier(snap(7, 0).comparables())).isEqualTo(LanguageTier.C);   // < 8
        assertThat(sut.earnedTier(snap(8, 0).comparables())).isEqualTo(LanguageTier.B);   // 8–24
        assertThat(sut.earnedTier(snap(24, 3).comparables())).isEqualTo(LanguageTier.B);
        assertThat(sut.earnedTier(snap(25, 3).comparables())).isEqualTo(LanguageTier.A);  // ≥25 incl ≥3 own
        assertThat(sut.earnedTier(snap(25, 2).comparables())).isEqualTo(LanguageTier.B);  // own too thin
    }

    @Test
    void segmentDropOneOverrideLowersTier() {
        stubLedger();
        PredictorSegmentStatus seg = new PredictorSegmentStatus();
        seg.setSegmentKey("techno|B101_300");
        seg.setLanguageTierOverride(PredictorSegmentStatus.OVERRIDE_DROP_ONE);
        when(segments.findById("techno|B101_300")).thenReturn(Optional.of(seg));
        when(scorer.score(any(), any(), isNull())).thenReturn(validOutput());

        PredictionScoringPipeline.Scored scored = sut.score(event(), snap(30, 5)); // earns A

        assertThat(scored.result().confidenceTier()).isEqualTo("B"); // A dropped one
    }

    @Test
    void qualitativeOverrideStripsNumericRangesButKeepsBand() {
        stubLedger();
        PredictorSegmentStatus seg = new PredictorSegmentStatus();
        seg.setSegmentKey("techno|B101_300");
        seg.setLanguageTierOverride(PredictorSegmentStatus.OVERRIDE_QUALITATIVE);
        when(segments.findById("techno|B101_300")).thenReturn(Optional.of(seg));
        when(scorer.score(any(), any(), isNull())).thenReturn(validOutput());

        PredictionScoringPipeline.Scored scored = sut.score(event(), snap(10, 1));

        assertThat(scored.result().attendanceRange()).isNull();   // MAPE tripwire: numeric → qualitative
        assertThat(scored.result().revenueRangeMinor()).isNull();
        assertThat(scored.result().selloutBand()).isNotNull();    // Brier metric untouched by MAPE wire
        assertThat(scored.result().benchmarkOnly()).isFalse();
    }

    @Test
    void comparablesBlockIsFeAligned() {
        stubLedger();
        when(scorer.score(any(), any(), isNull())).thenReturn(validOutput());

        PredictionScoringPipeline.Scored scored = sut.score(event(), snap(10, 1));

        PredictionResult.Comparables c = scored.result().comparables();
        assertThat(c.filters()).isEqualTo("techno · 101–300 · summer · Amsterdam");
        assertThat(c.aggregates()).containsEntry("events", 10).containsEntry("own", 1);
        assertThat(c.aggregates()).doesNotContainKeys("avgAttendance"); // foreign aggregate suppressed
    }

    /**
     * predictor-edge-3: PredictionScoringJob writes predictor_segment_status keys from
     * event_outcomes.genre_family, which stores the MERGE key — so reading with the display
     * spelling silently never matched and the §5 tripwire downgrade stopped applying.
     */
    @Test
    void segmentTripwireMatchesRegardlessOfTheGenresDisplayCase() {
        stubLedger();
        PredictorSegmentStatus seg = new PredictorSegmentStatus();
        seg.setSegmentKey("techno|B101_300");
        seg.setLanguageTierOverride(PredictorSegmentStatus.OVERRIDE_DROP_ONE);
        when(segments.findById("techno|B101_300")).thenReturn(Optional.of(seg));
        when(scorer.score(any(), any(), isNull())).thenReturn(validOutput());

        PredictionScoringPipeline.Scored scored = sut.score(event(), snapWithGenre(" Techno "));

        assertThat(scored.result().confidenceTier()).isEqualTo("B"); // A dropped one
    }

    /**
     * predictor-edge-9: the un-relaxed case (the common one) shipped the literal "NONE", which
     * is truthy — so the surface prefixed it with "Net widened" and named the rung with a Java
     * constant. Null is the honest answer, and NON_NULL keeps the key out of the payload; the
     * ledger's internal comparables JSON keeps the enum name for machine reads.
     */
    @Test
    void unrelaxedComparablesShipNoRelaxationAtAll() throws Exception {
        stubLedger();
        when(scorer.score(any(), any(), isNull())).thenReturn(validOutput());

        PredictionScoringPipeline.Scored scored = sut.score(event(), snap(10, 1));

        assertThat(scored.result().comparables().relaxation()).isNull();
        assertThat(com.imin.iminapi.predictor.service.PredictorJson.MAPPER
                .writeValueAsString(scored.result())).doesNotContain("relaxation");
        ArgumentCaptor<PredictionLedgerService.RecordCommand> cap =
                ArgumentCaptor.forClass(PredictionLedgerService.RecordCommand.class);
        verify(ledger).record(cap.capture());
        assertThat(cap.getValue().comparablesJson()).contains("\"relaxation\":\"NONE\"");
    }

    @Test
    void widenedComparablesShipADisplayPhraseNotTheEnumName() {
        stubLedger();
        when(scorer.score(any(), any(), isNull())).thenReturn(validOutput());

        PredictionScoringPipeline.Scored scored = sut.score(event(), relaxedSnap("CITY_TO_COUNTRY"));

        assertThat(scored.result().comparables().relaxation()).isEqualTo("across the country");
    }
}
