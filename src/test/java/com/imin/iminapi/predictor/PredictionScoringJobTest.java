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
import java.util.ArrayList;
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

    /**
     * A repository stub that behaves like the real paged query: it serves the head of a mutable
     * "not yet joined" list, and a joined row leaves that list — which is what makes the job's
     * paging (and its starvation guard) observable.
     */
    private void serve(PredictionLedgerRepository ledger, List<PredictionLedger> remaining) {
        when(ledger.findJoinable(any())).thenAnswer(inv -> {
            int size = inv.getArgument(0, org.springframework.data.domain.Pageable.class).getPageSize();
            return List.copyOf(remaining.subList(0, Math.min(size, remaining.size())));
        });
    }

    @Test
    void pagesThroughEveryJoinableRenderNotJustTheFirstPage() {
        PredictionLedgerRepository ledger = mock(PredictionLedgerRepository.class);
        EventOutcomeRepository outcomes = mock(EventOutcomeRepository.class);
        PredictionLedgerService service = mock(PredictionLedgerService.class);

        List<PredictionLedger> remaining = new ArrayList<>();
        for (int i = 0; i < 600; i++) {                        // > one 500-row page
            UUID eventId = UUID.randomUUID();
            UUID rowId = UUID.randomUUID();
            remaining.add(render(rowId, eventId));
            when(outcomes.findById(eventId)).thenReturn(Optional.of(finalized(eventId, 240, 198)));
        }
        serve(ledger, remaining);
        doAnswer(inv -> {                                      // joining drops the row from the set
            UUID id = inv.getArgument(0, UUID.class);
            remaining.removeIf(r -> r.getId().equals(id));
            return null;
        }).when(service).joinOutcome(any(), any(), any(), any(), any(), any());

        new PredictionScoringJob(ledger, outcomes, service, mock(PredictorSegmentStatusRepository.class), clock).run();

        verify(service, times(600)).joinOutcome(any(), any(), any(), eq(now), any(), any());
    }

    @Test
    void oneStuckRenderDoesNotBlockTheRestOfTheBacklog() {
        PredictionLedgerRepository ledger = mock(PredictionLedgerRepository.class);
        EventOutcomeRepository outcomes = mock(EventOutcomeRepository.class);
        PredictionLedgerService service = mock(PredictionLedgerService.class);

        List<PredictionLedger> remaining = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            UUID eventId = UUID.randomUUID();
            remaining.add(render(UUID.randomUUID(), eventId));
            when(outcomes.findById(eventId)).thenReturn(Optional.of(finalized(eventId, 240, 198)));
        }
        UUID stuck = remaining.get(0).getId();                 // permanently fails, never leaves the set
        serve(ledger, remaining);
        doAnswer(inv -> {
            UUID id = inv.getArgument(0, UUID.class);
            if (id.equals(stuck)) throw new IllegalStateException("boom");
            remaining.removeIf(r -> r.getId().equals(id));
            return null;
        }).when(service).joinOutcome(any(), any(), any(), any(), any(), any());

        new PredictionScoringJob(ledger, outcomes, service, mock(PredictorSegmentStatusRepository.class), clock).run();

        verify(service, times(600)).joinOutcome(any(), any(), any(), eq(now), any(), any()); // 599 done + the failure
        verify(service, times(1)).joinOutcome(eq(stuck), any(), any(), any(), any(), any());
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

        serve(ledger, new ArrayList<>(List.of(
                render(rDone, doneEvent), render(rPending, pendingEvent), render(rNoOutcome, noOutcomeEvent))));
        when(outcomes.findById(doneEvent)).thenReturn(Optional.of(finalized(doneEvent, 240, 198)));
        when(outcomes.findById(pendingEvent)).thenReturn(Optional.of(notFinalized(pendingEvent)));
        when(outcomes.findById(noOutcomeEvent)).thenReturn(Optional.empty());

        new PredictionScoringJob(ledger, outcomes, service, mock(PredictorSegmentStatusRepository.class), clock).run();

        verify(service, times(1)).joinOutcome(eq(rDone), eq(240), eq(198), eq(now), isNull(), isNull());
        verify(service, never()).joinOutcome(eq(rPending), any(), any(), any(), any(), any());
        verify(service, never()).joinOutcome(eq(rNoOutcome), any(), any(), any(), any(), any());
    }
}
