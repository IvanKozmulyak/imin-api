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
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
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
    private final CompetingNightsService competingNights = mock(CompetingNightsService.class);
    private final WeatherService weather = mock(WeatherService.class);
    private final PredictorProperties props = new PredictorProperties();

    private final ReforecastService sut = new ReforecastService(
            events, tiers, pacingCurves, engine, trajectories, ledgerService, ledgerRepo,
            narrator, alertNotifier, competingNights, weather, props, clock);

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
        when(competingNights.compute(any())).thenReturn(CompetingNightsService.CompetingNights.NONE);
        when(weather.forecast(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(null);

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

    /**
     * predictor-edge-8: {@code band} must stay the MACHINE code — {@code ReforecastService}
     * parses it back with {@code ProjectionBand.valueOf} on every recompute and folds it into
     * the ledger input hash — so the honest phrase ships beside it as {@code bandLabel}. The
     * organizer's chip rendered the raw constant ("TRACKING_60_85") in all four locales until
     * this existed.
     */
    @Test
    void servesTheBandCodeAndItsDisplayPhraseSideBySide() {
        stubBands(ProjectionBand.TRACKING_60_85);
        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        assertThat(r.band()).isEqualTo("TRACKING_60_85");                          // machine code
        assertThat(r.bandLabel()).isEqualTo("tracking 60–85% of capacity");        // display phrase
        assertThat(r.bandLabel()).isEqualTo(ProjectionBand.TRACKING_60_85.phrase());
    }

    /** A ledger row written before bandLabel existed still serves a renderable label. */
    @Test
    void servableLedgerRowWithoutBandLabelGetsThePhraseFilledIn() {
        stubBands(ProjectionBand.TRACKING_60_85);
        sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        // Strip the field from the persisted row, exactly as a pre-upgrade row looks.
        PredictionLedger row = rows.get(0);
        row.setOutputJson(row.getOutputJson().replace("\"bandLabel\":\"tracking 60–85% of capacity\",", ""));
        assertThat(row.getOutputJson()).doesNotContain("bandLabel");

        ReforecastResult served = sut.latestServable(eventId);

        assertThat(served.band()).isEqualTo("TRACKING_60_85");
        assertThat(served.bandLabel()).isEqualTo("tracking 60–85% of capacity");
    }

    @Test
    void stage0InterimCarriesTheBandLabelToo() {
        when(pacingCurves.lookup(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        seedPrePublish(120, 170);

        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        assertThat(r.stage()).isEqualTo(0);
        assertThat(r.band()).isNotNull();
        assertThat(r.bandLabel()).isEqualTo(ProjectionBand.valueOf(r.band()).phrase());
    }

    /**
     * predictor-edge-3: the curves are keyed off event_outcomes.city / genre_family, which store
     * MERGE keys — looking up with a display spelling would miss the event's own segment.
     */
    @Test
    void pacingCurveIsLookedUpByTheMergeKeysNotTheDisplaySpellings() {
        Event e = events.findActive(eventId).orElseThrow();
        e.setVenueCity("Den Haag");
        e.setGenre(" House & Techno ");
        stubBands(ProjectionBand.TRACKING_60_85);

        sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        verify(pacingCurves).lookup(org.mockito.ArgumentMatchers.eq("den haag"),
                org.mockito.ArgumentMatchers.eq("NL"),
                org.mockito.ArgumentMatchers.eq("house & techno"), any(), any());
    }

    /**
     * predictor-edge-9: the pacing block's relaxation is a display phrase and is absent at the
     * un-relaxed rung; the ledger's internal comparables JSON keeps the enum name.
     */
    @Test
    void pacingRelaxationIsAPhraseAndAbsentWhenTheNetWasNotWidened() {
        stubBands(ProjectionBand.TRACKING_60_85);
        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        assertThat(r.pacing().relaxation()).isNull();                       // NONE ⇒ nothing to say
        assertThat(rows.get(0).getComparablesJson()).contains("\"relaxation\":\"NONE\"");

        when(pacingCurves.lookup(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new CurveMatch(RelaxationLevel.CITY_TO_COUNTRY, curve())));
        stubBands(ProjectionBand.TRACKING_60_85);
        ReforecastResult widened = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        assertThat(widened.pacing().relaxation()).isEqualTo("across the country");
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

    // ---- narration credit ------------------------------------------------------

    private static final ReforecastResult.NarrationCredit CREDIT =
            new ReforecastResult.NarrationCredit("Weather data provided by OpenWeather", "https://openweathermap.org/");

    @Test
    void weatherUsedSetsNarrationCreditAndPassesEventCoords() {
        Event e = events.findActive(eventId).orElseThrow();
        e.setVenueLatitude(48.85661);
        e.setVenueLongitude(2.35222);
        e.setTimezone("Europe/Paris");
        when(weather.forecast(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new WeatherService.Weather(40, 18.0));
        when(weather.credit()).thenReturn(CREDIT);
        stubBands(ProjectionBand.TRACKING_60_85);

        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        assertThat(r.narrationCredit()).isEqualTo(CREDIT);
        verify(weather).forecast(eq(48.85661), eq(2.35222), eq("Amsterdam"), eq("NL"),
                eq(java.time.ZoneId.of("Europe/Paris")), eq(java.time.LocalDate.parse("2026-06-21")), eq(20));
        ArgumentCaptor<ReforecastNarrator.Context> ctx = ArgumentCaptor.forClass(ReforecastNarrator.Context.class);
        verify(narrator).narrate(ctx.capture());
        assertThat(ctx.getValue().weatherPrecipPct()).isEqualTo(40);
        assertThat(ctx.getValue().weatherTempC()).isEqualTo(18.0);
    }

    @Test
    void noWeatherNoCredit() {
        when(weather.credit()).thenReturn(CREDIT);
        stubBands(ProjectionBand.TRACKING_60_85);

        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        assertThat(r.narration()).isNotNull();
        assertThat(r.narrationCredit()).isNull();
    }

    @Test
    void narratorFailureNoCredit() {
        when(weather.forecast(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new WeatherService.Weather(40, 18.0));
        when(weather.credit()).thenReturn(CREDIT);
        when(narrator.narrate(any())).thenThrow(new IllegalStateException("upstream down"));
        stubBands(ProjectionBand.TRACKING_60_85);

        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        assertThat(r.narration()).isNull();
        assertThat(r.narrationCredit()).isNull();
    }

    @Test
    void unchangedBandReusesPriorCredit() {
        when(weather.forecast(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new WeatherService.Weather(40, 18.0));
        when(weather.credit()).thenReturn(CREDIT);
        stubBands(ProjectionBand.TRACKING_60_85, ProjectionBand.TRACKING_60_85);
        sut.recompute(eventId, ReforecastTrigger.SCHEDULED); // writes the prior row with the credit
        clearInvocations(weather);
        when(weather.credit()).thenReturn(new ReforecastResult.NarrationCredit("other", "https://other.invalid/"));

        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        assertThat(r.narrationCredit()).isEqualTo(CREDIT);  // read back from the ledger row
        verify(weather, never()).forecast(any(), any(), any(), any(), any(), any(), anyInt());
    }

    /** A ledger row written before narrationCredit existed parses with the credit null, as served and as prior. */
    @Test
    void ledgerRowWithoutNarrationCreditParsesWithCreditNull() {
        when(weather.forecast(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new WeatherService.Weather(40, 18.0));
        when(weather.credit()).thenReturn(CREDIT);
        stubBands(ProjectionBand.TRACKING_60_85, ProjectionBand.TRACKING_60_85);
        sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        PredictionLedger row = rows.get(0);
        row.setOutputJson(row.getOutputJson().replaceAll("\"narrationCredit\":\\{[^}]*\\},", ""));
        assertThat(row.getOutputJson()).doesNotContain("narrationCredit");

        ReforecastResult served = sut.latestServable(eventId);
        assertThat(served.band()).isEqualTo("TRACKING_60_85");
        assertThat(served.narration()).isEqualTo("Pacing behind comparable events.");
        assertThat(served.narrationCredit()).isNull();

        // same band: the old row's narration is reused, still with no credit
        ReforecastResult next = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        assertThat(next.narration()).isEqualTo("Pacing behind comparable events.");
        assertThat(next.narrationCredit()).isNull();
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
        assertThat(r.bandLabel()).isNull();                       // no band ⇒ no label to render
        assertThat(r.generatedAt()).isEqualTo(now);               // timestamp still present
    }

    /**
     * predictor-edge-14: {@code classify(mid, 0)} answered UNDER_60, so an event whose tiers were
     * removed or zeroed after it was scored was served a "ready" chip reading "tracking below 60%
     * of capacity" — and a crossing into it fired a dashboard notification — about an event with
     * no capacity at all. Unknown capacity is not a tiny capacity.
     */
    @Test
    void capacityZeroServesNoBandAndNoAlert() {
        when(tiers.sumQuantityByEventId(eventId)).thenReturn(0);
        when(pacingCurves.lookup(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        seedPrePublish(120, 170);   // a real pre-publish range still exists in the ledger

        ReforecastResult r = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        assertThat(ProjectionBand.classify(150, 0)).isNull();
        assertThat(r.status()).isEqualTo("insufficient_data");
        assertThat(r.band()).isNull();
        assertThat(r.bandLabel()).isNull();
        assertThat(r.projectedFinalRange()).isNull();
        verify(alertNotifier, never()).notifyBandChange(any(), any(), any(), any());
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

    @Test
    void recomputeStillReturnsWhenTheAlertClaimThrows() {
        PredictorAlertStore store = mock(PredictorAlertStore.class);
        when(store.claim(any(), any(), any(), any())).thenThrow(new IllegalStateException("claim failed"));
        ReforecastAlertNotifier realNotifier = new ReforecastAlertNotifier(
                mock(com.imin.iminapi.repository.NotificationRepository.class),
                mock(com.imin.iminapi.repository.NotificationPreferencesRepository.class), store, events,
                mock(com.imin.iminapi.repository.OrganizationRepository.class),
                mock(com.imin.iminapi.repository.UserRepository.class),
                mock(com.imin.iminapi.email.EmailService.class), new com.imin.iminapi.email.EmailTemplateRenderer(),
                new com.imin.iminapi.email.EmailProperties(), clock);
        ReforecastService withRealNotifier = new ReforecastService(
                events, tiers, pacingCurves, engine, trajectories, ledgerService, ledgerRepo,
                narrator, realNotifier, competingNights, weather, props, clock);
        stubBands(ProjectionBand.TRACKING_85_100, ProjectionBand.TRACKING_60_85);

        withRealNotifier.recompute(eventId, ReforecastTrigger.SCHEDULED);
        ReforecastResult crossed = withRealNotifier.recompute(eventId, ReforecastTrigger.MANUAL);

        verify(store).claim(any(), any(), any(), any());
        assertThat(crossed.band()).isEqualTo("TRACKING_60_85");
    }

    /**
     * predictor-edge-1: the alert's tone is the platform's tone vocabulary (green/amber). It
     * shipped "up"/"down", which that vocabulary has no member for — so an up-crossing (the good
     * news the alert exists to deliver) and a down-crossing rendered as the identical neutral
     * chip and the direction signal was lost.
     */
    @Test
    void bandCrossingToneIsGreenUpAndAmberDown() {
        stubBands(
                ProjectionBand.TRACKING_85_100, // establish
                ProjectionBand.TRACKING_60_85,  // weakened → amber
                ProjectionBand.TRACKING_85_100  // recovered → green
        );
        sut.recompute(eventId, ReforecastTrigger.SCHEDULED);

        ReforecastResult weakened = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        assertThat(weakened.alert().tone()).isEqualTo("amber");
        assertThat(weakened.alert().was()).isEqualTo(ProjectionBand.TRACKING_85_100.phrase());
        assertThat(weakened.alert().now()).isEqualTo(ProjectionBand.TRACKING_60_85.phrase());

        ReforecastResult recovered = sut.recompute(eventId, ReforecastTrigger.SCHEDULED);
        assertThat(recovered.alert().tone()).isEqualTo("green");
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
