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

    private final PredictionScoringPipeline sut = new PredictionScoringPipeline(
            snapshots, scorer, new PredictionGuardrailValidator(), ledger, segments, props, clock);

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

    private Stage0Output validOutput() {
        return new Stage0Output(
                new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210),
                new PredictionResult.LongRange(120 * 1500L, 210 * 2400L),
                List.of(new PredictionResult.Factor("Saturday in summer", "supporting", "comparable Saturdays outperform"),
                        new PredictionResult.Factor("Prices inside band", "supporting", "tier prices within comparable range"),
                        new PredictionResult.Factor("Low own history", "opposing", "organizer has few completed events")),
                List.of());
    }

    private Stage0Output invalidOutput() {
        // Over-capacity attendance — deterministic validator rejection.
        return new Stage0Output(new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 9_999), null,
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
}
