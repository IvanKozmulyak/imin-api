package com.imin.iminapi.predictor;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.ReforecastTrigger;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot;
import com.imin.iminapi.predictor.service.PredictionScoringPipeline;
import com.imin.iminapi.predictor.service.PredictorReactivityEvents;
import com.imin.iminapi.predictor.service.PredictorReactivityService;
import com.imin.iminapi.predictor.service.ReforecastService;
import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task scope B (organizer-action reactivity): draft re-score only when already scored + hash
 * short-circuit, live re-forecast, burst debounce, per-event daily system cap, and the publish
 * baseline. Ledger reads are backed by a controllable in-memory list.
 */
class PredictorReactivityServiceTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
    }

    private final EventRepository events = mock(EventRepository.class);
    private final PredictionScoringPipeline pipeline = mock(PredictionScoringPipeline.class);
    private final PredictionLedgerRepository ledgerRepo = mock(PredictionLedgerRepository.class);
    private final ReforecastService reforecast = mock(ReforecastService.class);
    private final PredictorProperties props = new PredictorProperties(); // debounce 120s, cap 10
    private final MutableClock clock = new MutableClock();

    private final PredictorReactivityService sut =
            new PredictorReactivityService(events, pipeline, ledgerRepo, reforecast, props, clock);

    private final UUID eventId = UUID.randomUUID();
    private final List<PredictionLedger> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(ledgerRepo.findByEventIdOrderByCreatedAtDesc(eventId)).thenReturn(rows);
        PredictionInputSnapshot snap = mock(PredictionInputSnapshot.class);
        when(snap.sha256()).thenReturn("HASH_NEW");
        when(pipeline.snapshot(any())).thenReturn(snap);
    }

    private Event event(EventStatus status) {
        Event e = new Event();
        e.setId(eventId);
        e.setStatus(status);
        when(events.findActive(eventId)).thenReturn(Optional.of(e));
        return e;
    }

    private void seedPrediction(String hash) {
        PredictionLedger r = new PredictionLedger();
        r.setId(UUID.randomUUID());
        r.setEventId(eventId);
        r.setSurface(PredictionSurface.PRE_PUBLISH);
        r.setInputSnapshotHash(hash);
        rows.add(0, r);
    }

    // ---- draft re-score --------------------------------------------------------

    @Test
    void draftEditDoesNotScoreWhenNeverScored() {
        event(EventStatus.DRAFT); // no prediction seeded
        sut.onEventMutated(new PredictorReactivityEvents.EventMutated(eventId));
        verify(pipeline, never()).score(any(), any(), any());
    }

    @Test
    void draftEditRescoresWhenAlreadyScoredAndHashChanged() {
        event(EventStatus.DRAFT);
        seedPrediction("HASH_OLD"); // differs from the new snapshot hash
        sut.onEventMutated(new PredictorReactivityEvents.EventMutated(eventId));
        verify(pipeline, times(1)).score(any(), any(), any());
    }

    @Test
    void draftEditShortCircuitsWhenHashUnchanged() {
        event(EventStatus.DRAFT);
        seedPrediction("HASH_NEW"); // equals the new snapshot hash → no-op
        sut.onEventMutated(new PredictorReactivityEvents.EventMutated(eventId));
        verify(pipeline, never()).score(any(), any(), any());
    }

    // ---- live re-forecast + debounce -------------------------------------------

    @Test
    void liveEditReforecastsAndDebouncesBursts() {
        event(EventStatus.LIVE);
        sut.onEventMutated(new PredictorReactivityEvents.EventMutated(eventId)); // runs
        clock.advanceSeconds(30);
        sut.onEventMutated(new PredictorReactivityEvents.EventMutated(eventId)); // coalesced (< 120s)
        clock.advanceSeconds(121);
        sut.onEventMutated(new PredictorReactivityEvents.EventMutated(eventId)); // runs
        verify(reforecast, times(2)).recompute(eq(eventId), eq(ReforecastTrigger.EDIT));
    }

    // ---- system daily cap ------------------------------------------------------

    @Test
    void systemDailyCapStopsRunawayRescores() {
        event(EventStatus.LIVE);
        // Advance past the debounce each iteration so only the cap limits throughput.
        for (int i = 0; i < 15; i++) {
            sut.onEventMutated(new PredictorReactivityEvents.EventMutated(eventId));
            clock.advanceSeconds(121);
        }
        verify(reforecast, times(props.getSystemRescoresPerEventPerDay()))
                .recompute(eq(eventId), eq(ReforecastTrigger.EDIT));
    }

    // ---- publish baseline ------------------------------------------------------

    @Test
    void publishFiresBaselineOnlyWhenDraftWasScored() {
        // no prediction → no baseline
        sut.onEventPublished(new PredictorReactivityEvents.EventPublished(eventId));
        verify(reforecast, never()).recompute(any(), any());

        // scored → baseline
        seedPrediction("HASH_OLD");
        sut.onEventPublished(new PredictorReactivityEvents.EventPublished(eventId));
        verify(reforecast, times(1)).recompute(eq(eventId), eq(ReforecastTrigger.PUBLISH));
    }
}
