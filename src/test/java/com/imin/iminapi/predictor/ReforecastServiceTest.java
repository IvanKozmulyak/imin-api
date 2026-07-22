package com.imin.iminapi.predictor;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.dto.ReforecastResult;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.ProjectionBand;
import com.imin.iminapi.predictor.model.RelaxationLevel;
import com.imin.iminapi.predictor.model.ReforecastTrigger;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.*;
import com.imin.iminapi.predictor.service.PacingCurveService.CurveMatch;
import com.imin.iminapi.predictor.service.PacingEngine.Curve;
import com.imin.iminapi.predictor.service.PacingEngine.CurvePoint;
import com.imin.iminapi.predictor.service.PacingEngine.Projection;
import com.imin.iminapi.predictor.service.SalesTrajectoryService.NormalizedCurve;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tasks 2+3 (recompute cadence + trajectory alerts, 86cav479j/86cav479r): idempotency,
 * narration-only-on-band-change, kill-switch (numbers survive, narration doesn't), Stage 0
 * interim / insufficient fallback, ledger-row-per-recompute, and the exactly-once alert fixture.
 *
 * <p>The ledger is simulated in-memory so prior-band comparisons are faithful: {@code record}
 * prepends a real {@link PredictionLedger} row that the repo lookup then returns.
 */
class ReforecastServiceTest {

    private final Instant now = Instant.parse("2026-06-01T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private final EventRepository events = mock(EventRepository.class);
    private final TicketTierRepository tiers = mock(TicketTierRepository.class);
    private final PacingCurveService pacingCurves = mock(PacingCurveService.class);
    private final PacingEngine engine = mock(PacingEngine.class);
    private final SalesTrajectoryService trajectories = mock(SalesTrajectoryService.class);
    private final PredictionLedgerService ledgerService = mock(PredictionLedgerService.class);
    private final PredictionLedgerRepository ledgerRepo = mock(PredictionLedgerRepository.class);
    private final ReforecastNarrator narrator = mock(ReforecastNarrator.class);
    private final ReforecastAlertNotifier alertNotifier = mock(ReforecastAlertNotifier.class);
    private final PredictorProperties props = new PredictorProperties();

    private final ReforecastService sut = new ReforecastService(
            events, tiers, pacingCurves, engine, trajectories, ledgerService, ledgerRepo,
            narrator, alertNotifier, props, clock);

    private final UUID eventId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final List<PredictionLedger> rows = new ArrayList<>(); // newest first

    @BeforeEach
    void setUp() {
        Event e = new Event();
        e.setId(eventId);
        e.setOrgId(orgId);
        e.setName("Warehouse Night");
        e.setCreatedBy(UUID.randomUUID());
        e.setTimezone("UTC");
        e.setVenueCity("Amsterdam");
        e.setVenueCountry("NL");
        e.setGenre("techno");
        e.setStartsAt(now.plus(20, ChronoUnit.DAYS)); // 20 days out → pacing horizon
        when(events.findActive(eventId)).thenReturn(Optional.of(e));

        when(tiers.sumQuantityByEventId(eventId)).thenReturn(200);
        when(tiers.sumSoldByEventId(eventId)).thenReturn(90);
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setSold(90);
        t.setPriceMinor(2000);
        when(tiers.findByEventIdOrderBySortOrderAsc(eventId)).thenReturn(List.of(t));

        when(trajectories.normalizedCurve(eventId)).thenReturn(new NormalizedCurve(eventId, 90, List.of()));
        when(trajectories.velocityPerDayLast7(any(), any())).thenReturn(4.0);

        when(pacingCurves.lookup(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new CurveMatch(RelaxationLevel.NONE, curve())));

        when(narrator.modelId()).thenReturn("test/model");
        when(narrator.narrate(any())).thenReturn("Pacing behind comparable events.");

        // In-memory ledger: record() prepends a faithful row; the repo returns the live list.
        when(ledgerService.record(any())).thenAnswer(inv -> {
            PredictionLedgerService.RecordCommand cmd = inv.getArgument(0);
            PredictionLedger row = new PredictionLedger();
            row.setId(UUID.randomUUID());
            row.setEventId(cmd.eventId());
            row.setOrgId(cmd.orgId());
            row.setSurface(cmd.surface());
            row.setStage((short) cmd.stage());
            row.setModelId(cmd.modelId());
            row.setPromptVersion(cmd.promptVersion());
            row.setInputSnapshotHash(cmd.inputSnapshotHash());
            row.setComparablesJson(cmd.comparablesJson());
            row.setOutputJson(cmd.outputJson());
            row.setCreatedAt(now);
            rows.add(0, row);
            return row.getId();
        });
        when(ledgerRepo.findByEventIdOrderByCreatedAtDesc(eventId)).thenReturn(rows);
    }

    private Curve curve() {
        return new Curve(15, List.of(
                new CurvePoint(20, 0.30, 0.20, 0.45),
                new CurvePoint(10, 0.55, 0.40, 0.70),
                new CurvePoint(0, 1.0, 1.0, 1.0)));
    }

    private Projection band(ProjectionBand b) {
        return switch (b) {
            case UNDER_60 -> new Projection(false, 80, 110, b, null, null);
            case TRACKING_60_85 -> new Projection(false, 130, 160, b, null, null);
            case TRACKING_85_100 -> new Projection(false, 175, 195, b, null, null);
            case SELL_OUT_LIKELY -> new Projection(false, 200, 200, b, 5, 2);
        };
    }

    private void stubBands(ProjectionBand... seq) {
        var stub = when(engine.project(any(), anyInt(), anyInt(), anyInt()));
        for (ProjectionBand b : seq) stub = stub.thenReturn(band(b));
    }

    // ---- ledger + stage --------------------------------------------------------

    @Test
    void everyRecomputeWritesOneLedgerRowAtStageOne() {
        stubBands(ProjectionBand.TRACKING_60_85, ProjectionBand.TRACKING_60_85);
        ReforecastResult r1 = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        sut.recompute(eventId, ReforecastTrigger.MANUAL);

        assertThat(r1.status()).isEqualTo("ready");
        assertThat(r1.stage()).isEqualTo(1);
        assertThat(r1.band()).isEqualTo("TRACKING_60_85");
        assertThat(r1.pacing()).isNotNull();
        assertThat(r1.ledger()).isNotNull();
        assertThat(r1.ledger().promptVersion()).isEqualTo(ReforecastNarrator.PROMPT_VERSION);
        verify(ledgerService, times(2)).record(any());       // one row per recompute
        assertThat(rows).allMatch(row -> row.getSurface() == PredictionSurface.REFORECAST);
    }

    @Test
    void projectedFinalRangeYieldsRevenueAndVelocityArithmetic() {
        stubBands(ProjectionBand.TRACKING_60_85);
        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        // revenue = projected sold × realized avg price (2000 minor); velocity from trajectory.
        assertThat(r.revenueRangeMinor().low()).isEqualTo(130 * 2000L);
        assertThat(r.revenueRangeMinor().high()).isEqualTo(160 * 2000L);
        assertThat(r.velocity()).isEqualTo(4.0);
    }

    // ---- narration -------------------------------------------------------------

    @Test
    void narrationRegeneratesOnlyOnBandChange() {
        stubBands(ProjectionBand.UNDER_60, ProjectionBand.UNDER_60, ProjectionBand.TRACKING_60_85);
        sut.recompute(eventId, ReforecastTrigger.SCHEDULED); // no prior → narrate
        sut.recompute(eventId, ReforecastTrigger.SCHEDULED); // same band → reuse
        sut.recompute(eventId, ReforecastTrigger.SCHEDULED); // band change → narrate

        verify(narrator, times(2)).narrate(any());
    }

    @Test
    void killSwitchServesArithmeticWithoutNarration() {
        props.setBenchmarkOnly(true);
        stubBands(ProjectionBand.TRACKING_60_85);
        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        assertThat(r.status()).isEqualTo("ready");
        assertThat(r.projectedFinalRange()).isNotNull();  // numbers survive the kill switch
        assertThat(r.stage()).isEqualTo(1);
        assertThat(r.narration()).isNull();               // narration does not
        verify(narrator, never()).narrate(any());
    }

    // ---- idempotency -----------------------------------------------------------

    @Test
    void recomputeIsIdempotentForSameInputs() {
        stubBands(ProjectionBand.TRACKING_60_85, ProjectionBand.TRACKING_60_85);
        ReforecastResult r1 = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        ReforecastResult r2 = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        // Ledger id differs per row; the projection itself is identical.
        assertThat(r2.withLedger(null)).isEqualTo(r1.withLedger(null));
    }

    // ---- interim / insufficient ------------------------------------------------

    @Test
    void fallsBackToStage0InterimFromLatestPrePublishWhenNoCurve() {
        when(pacingCurves.lookup(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        seedPrePublish(120, 170); // pre-publish attendance range 120–170

        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        assertThat(r.status()).isEqualTo("ready");
        assertThat(r.stage()).isEqualTo(0);                       // EXPLICITLY labelled interim
        assertThat(r.pacing()).isNull();                          // no pacing block at stage 0
        assertThat(r.projectedFinalRange().low()).isEqualTo(120); // clamped within capacity 200
    }

    @Test
    void insufficientDataWhenNoCurveAndNoPrePublish() {
        when(pacingCurves.lookup(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        assertThat(r.status()).isEqualTo("insufficient_data");
        assertThat(r.band()).isNull();
        assertThat(r.generatedAt()).isEqualTo(now);               // timestamp still present
    }

    // ---- trajectory alert: exactly once per crossing ---------------------------

    @Test
    void bandCrossingFiresExactlyOneAlertDownThenOneRecoveryAndZeroWithinBand() {
        stubBands(
                ProjectionBand.TRACKING_85_100, // establish (no prior → no alert)
                ProjectionBand.TRACKING_85_100, // same → 0
                ProjectionBand.TRACKING_60_85,  // slow-down (down) → 1
                ProjectionBand.TRACKING_60_85,  // same → 0
                ProjectionBand.TRACKING_85_100  // recovery (up) → 1
        );
        for (int i = 0; i < 5; i++) sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        verify(alertNotifier, times(2)).notifyBandChange(any(), any(), any(), any());
    }

    // ---- helpers ---------------------------------------------------------------

    private void seedPrePublish(int attLow, int attHigh) {
        PredictionResult pre = new PredictionResult(
                "pre_publish", 0, "B",
                new PredictionResult.Band(40, 70),
                new PredictionResult.Range(attLow, attHigh),
                null, List.of(), List.of(), null, false, "m", "1.0.0", now);
        PredictionLedger row = new PredictionLedger();
        row.setId(UUID.randomUUID());
        row.setEventId(eventId);
        row.setOrgId(orgId);
        row.setSurface(PredictionSurface.PRE_PUBLISH);
        row.setStage((short) 0);
        row.setModelId("m");
        row.setPromptVersion("1.0.0");
        row.setInputSnapshotHash("h");
        row.setComparablesJson("{}");
        try {
            row.setOutputJson(PredictorJson.MAPPER.writeValueAsString(pre));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        row.setCreatedAt(now);
        rows.add(row);
    }
}
