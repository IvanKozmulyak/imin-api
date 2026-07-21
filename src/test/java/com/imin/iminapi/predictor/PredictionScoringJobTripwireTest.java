package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictorSegmentStatus;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorSegmentStatusRepository;
import com.imin.iminapi.predictor.service.PredictionLedgerService;
import com.imin.iminapi.predictor.service.PredictionScoringJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tripwire math (task 86cav477c, spec §5): Brier/MAPE triggers fire at ≥20 scored renders,
 * never below; downgrades are automatic and merge severity only; there is NO auto-upgrade.
 */
class PredictionScoringJobTripwireTest {

    private final Instant now = Instant.parse("2026-08-01T05:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private PredictionLedgerRepository ledger;
    private EventOutcomeRepository outcomes;
    private PredictorSegmentStatusRepository segments;
    private PredictionScoringJob sut;
    private final Map<UUID, EventOutcome> outcomeByEvent = new HashMap<>();

    @BeforeEach
    void setUp() {
        ledger = mock(PredictionLedgerRepository.class);
        outcomes = mock(EventOutcomeRepository.class);
        segments = mock(PredictorSegmentStatusRepository.class);
        sut = new PredictionScoringJob(ledger, outcomes, mock(PredictionLedgerService.class), segments, clock);
        when(ledger.findByOutcomeJoinedAtIsNull(any())).thenReturn(List.of());
        when(outcomes.findById(any())).thenAnswer(inv -> Optional.ofNullable(outcomeByEvent.get(inv.getArgument(0))));
        when(segments.findById(any())).thenReturn(Optional.empty());
    }

    /** One scored (outcome-joined) render in techno|B101_300 with the given metrics. */
    private PredictionLedger scoredRow(BigDecimal brier, BigDecimal ape, boolean sellOut) {
        UUID eventId = UUID.randomUUID();
        PredictionLedger r = new PredictionLedger();
        r.setId(UUID.randomUUID());
        r.setEventId(eventId);
        r.setOutcomeJoinedAt(now.minusSeconds(60));
        r.setBrierComponent(brier);
        r.setApe(ape);
        EventOutcome o = new EventOutcome();
        o.setEventId(eventId);
        o.setGenreFamily("techno");
        o.setCapacityBand(CapacityBand.B101_300);
        o.setSellOut(sellOut);
        o.setFinalizedAt(now.minusSeconds(120));
        outcomeByEvent.put(eventId, o);
        return r;
    }

    private PredictorSegmentStatus savedSegment() {
        ArgumentCaptor<PredictorSegmentStatus> cap = ArgumentCaptor.forClass(PredictorSegmentStatus.class);
        verify(segments).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void brierNotBeatingBaseRateAt20ScoredDropsOneTier() {
        // 20 renders, none sold out (base rate 0 → base Brier 0), model predicted ~90% each
        // (brier 0.81) — decisively not beating the base rate.
        List<PredictionLedger> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(scoredRow(new BigDecimal("0.810000"), null, false));
        when(ledger.findByOutcomeJoinedAtIsNotNull()).thenReturn(rows);

        sut.run();

        PredictorSegmentStatus s = savedSegment();
        assertThat(s.getSegmentKey()).isEqualTo("techno|B101_300");
        assertThat(s.getLanguageTierOverride()).isEqualTo(PredictorSegmentStatus.OVERRIDE_DROP_ONE);
        assertThat(s.getDowngradedAt()).isEqualTo(now);
        assertThat(s.getReason()).contains("Brier").contains("base-rate");
        assertThat(s.getScoredCount()).isEqualTo(20);
    }

    @Test
    void nineteenScoredRendersNeverTrip() {
        List<PredictionLedger> rows = new ArrayList<>();
        for (int i = 0; i < 19; i++) rows.add(scoredRow(new BigDecimal("0.810000"), new BigDecimal("0.900000"), false));
        when(ledger.findByOutcomeJoinedAtIsNotNull()).thenReturn(rows);

        sut.run();

        PredictorSegmentStatus s = savedSegment();
        assertThat(s.getLanguageTierOverride()).isNull(); // metrics recorded, tripwire silent below 20
        assertThat(s.getDowngradedAt()).isNull();
        assertThat(s.getBrier()).isNotNull();
    }

    @Test
    void mapeOver25PercentAt20ScoredForcesQualitative() {
        List<PredictionLedger> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(scoredRow(null, new BigDecimal("0.400000"), false));
        when(ledger.findByOutcomeJoinedAtIsNotNull()).thenReturn(rows);

        sut.run();

        PredictorSegmentStatus s = savedSegment();
        assertThat(s.getLanguageTierOverride()).isEqualTo(PredictorSegmentStatus.OVERRIDE_QUALITATIVE);
        assertThat(s.getReason()).contains("MAPE");
    }

    @Test
    void recoveredMetricsNeverAutoUpgrade() {
        // Segment already downgraded; new metrics are excellent — override must be retained.
        PredictorSegmentStatus existing = new PredictorSegmentStatus();
        existing.setSegmentKey("techno|B101_300");
        existing.setLanguageTierOverride(PredictorSegmentStatus.OVERRIDE_DROP_ONE);
        Instant originalDowngrade = now.minusSeconds(86_400);
        existing.setDowngradedAt(originalDowngrade);
        when(segments.findById("techno|B101_300")).thenReturn(Optional.of(existing));

        // Mixed outcomes (base rate 0.5 → base Brier 0.25) with near-perfect model brier.
        List<PredictionLedger> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(scoredRow(new BigDecimal("0.010000"), new BigDecimal("0.050000"), i % 2 == 0));
        when(ledger.findByOutcomeJoinedAtIsNotNull()).thenReturn(rows);

        sut.run();

        PredictorSegmentStatus s = savedSegment();
        assertThat(s.getLanguageTierOverride()).isEqualTo(PredictorSegmentStatus.OVERRIDE_DROP_ONE);
        assertThat(s.getDowngradedAt()).isEqualTo(originalDowngrade); // untouched — manual upgrade only
    }

    @Test
    void bothTripwiresMergeToDropOneQualitative() {
        List<PredictionLedger> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(scoredRow(new BigDecimal("0.810000"), new BigDecimal("0.400000"), false));
        when(ledger.findByOutcomeJoinedAtIsNotNull()).thenReturn(rows);

        sut.run();

        assertThat(savedSegment().getLanguageTierOverride())
                .isEqualTo(PredictorSegmentStatus.OVERRIDE_DROP_ONE_QUALITATIVE);
    }

    // ---- static scoring math ----------------------------------------------------

    @Test
    void brierComponentIsSquaredErrorOfBandMidpoint() {
        PredictionResult r = resultWithBand(40, 60, null);
        EventOutcome sold = new EventOutcome();
        sold.setSellOut(true);
        assertThat(PredictionScoringJob.brierComponent(r, sold))
                .isEqualByComparingTo(new BigDecimal("0.250000")); // (0.5 - 1)^2
        EventOutcome notSold = new EventOutcome();
        notSold.setSellOut(false);
        assertThat(PredictionScoringJob.brierComponent(r, notSold))
                .isEqualByComparingTo(new BigDecimal("0.250000")); // (0.5 - 0)^2
    }

    @Test
    void benchmarkOnlyRenderScoresNothing() {
        PredictionResult r = resultWithBand(-1, -1, null); // no band, no range
        EventOutcome o = new EventOutcome();
        o.setSellOut(true);
        o.setAttendance(100);
        assertThat(PredictionScoringJob.brierComponent(r, o)).isNull();
        assertThat(PredictionScoringJob.ape(r, o)).isNull();
    }

    @Test
    void apeIsMidpointErrorOverActual() {
        PredictionResult r = resultWithBand(-1, -1, new PredictionResult.Range(100, 200)); // mid 150
        EventOutcome o = new EventOutcome();
        o.setAttendance(100);
        assertThat(PredictionScoringJob.ape(r, o)).isEqualByComparingTo(new BigDecimal("0.500000"));
    }

    private static PredictionResult resultWithBand(int low, int high, PredictionResult.Range att) {
        PredictionResult.Band band = low < 0 ? null : new PredictionResult.Band(low, high);
        return new PredictionResult("pre_publish", 0, "B", band, att, null,
                List.of(), List.of(), null, false, "m", "1.0.0", Instant.now());
    }
}
