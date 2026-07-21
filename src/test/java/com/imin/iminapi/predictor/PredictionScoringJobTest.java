package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorSegmentStatusRepository;
import com.imin.iminapi.predictor.service.PredictionLedgerService;
import com.imin.iminapi.predictor.service.PredictionScoringJob;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Task 3 scoring-job scaffold — joins ledger renders only to FINALIZED outcomes, using the
 * outcome's sold/attendance; skips renders whose outcome is not finalized yet. Pure Mockito.
 */
class PredictionScoringJobTest {

    private final Instant now = Instant.parse("2026-05-01T05:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private PredictionLedger render(UUID id, UUID eventId) {
        PredictionLedger r = new PredictionLedger();
        r.setId(id);
        r.setEventId(eventId);
        return r;
    }

    private EventOutcome finalized(UUID eventId, int sold, int attendance) {
        EventOutcome o = new EventOutcome();
        o.setEventId(eventId);
        o.setSoldTotal(sold);
        o.setAttendance(attendance);
        o.setFinalizedAt(now.minusSeconds(3600));
        return o;
    }

    private EventOutcome notFinalized(UUID eventId) {
        EventOutcome o = new EventOutcome();
        o.setEventId(eventId);
        o.setFinalizedAt(null);
        return o;
    }

    @Test
    void joinsOnlyRendersWithFinalizedOutcomes() {
        PredictionLedgerRepository ledger = mock(PredictionLedgerRepository.class);
        EventOutcomeRepository outcomes = mock(EventOutcomeRepository.class);
        PredictionLedgerService service = mock(PredictionLedgerService.class);

        UUID doneEvent = UUID.randomUUID();
        UUID pendingEvent = UUID.randomUUID();
        UUID noOutcomeEvent = UUID.randomUUID();
        UUID rDone = UUID.randomUUID();
        UUID rPending = UUID.randomUUID();
        UUID rNoOutcome = UUID.randomUUID();

        when(ledger.findByOutcomeJoinedAtIsNull(any()))
                .thenReturn(List.of(render(rDone, doneEvent), render(rPending, pendingEvent), render(rNoOutcome, noOutcomeEvent)));
        when(outcomes.findById(doneEvent)).thenReturn(Optional.of(finalized(doneEvent, 240, 198)));
        when(outcomes.findById(pendingEvent)).thenReturn(Optional.of(notFinalized(pendingEvent)));
        when(outcomes.findById(noOutcomeEvent)).thenReturn(Optional.empty());

        new PredictionScoringJob(ledger, outcomes, service, mock(PredictorSegmentStatusRepository.class), clock).run();

        verify(service, times(1)).joinOutcome(eq(rDone), eq(240), eq(198), eq(now), isNull(), isNull());
        verify(service, never()).joinOutcome(eq(rPending), any(), any(), any(), any(), any());
        verify(service, never()).joinOutcome(eq(rNoOutcome), any(), any(), any(), any(), any());
    }
}
