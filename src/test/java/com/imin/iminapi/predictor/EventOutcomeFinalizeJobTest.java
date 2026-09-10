package com.imin.iminapi.predictor;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.service.EventOutcomeFinalizeJob;
import com.imin.iminapi.predictor.service.EventOutcomeService;
import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Task 1 finalize job — only events ended more than the grace ago are finalized;
 * events still within grace or with no end time are skipped. Pure Mockito, no Spring.
 */
class EventOutcomeFinalizeJobTest {

    private final Instant now = Instant.parse("2026-03-01T04:30:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private Event event(UUID id, Instant endsAt) {
        Event e = new Event();
        e.setId(id);
        e.setEndsAt(endsAt);
        return e;
    }

    private Event cancelledEvent(UUID id, Instant endsAt) {
        Event e = event(id, endsAt);
        e.setStatus(EventStatus.CANCELLED);
        return e;
    }

    private EventOutcome outcome(UUID eventId) {
        EventOutcome o = new EventOutcome();
        o.setEventId(eventId);
        return o;
    }

    /**
     * A repository stub that behaves like the real paged query: it serves the head of a mutable
     * "not yet finalized" list, and a finalized row leaves that list — which is what makes the
     * job's paging (and its starvation guard) observable.
     */
    private void serve(EventOutcomeRepository outcomes, List<EventOutcome> remaining) {
        when(outcomes.findDueForFinalize(any(), any())).thenAnswer(inv -> {
            int size = inv.getArgument(1, org.springframework.data.domain.Pageable.class).getPageSize();
            return List.copyOf(remaining.subList(0, Math.min(size, remaining.size())));
        });
    }

    @Test
    void pagesThroughEveryDueRowNotJustTheFirstPage() {
        EventOutcomeRepository outcomes = mock(EventOutcomeRepository.class);
        EventRepository events = mock(EventRepository.class);
        EventOutcomeService service = mock(EventOutcomeService.class);

        List<EventOutcome> remaining = new ArrayList<>();
        for (int i = 0; i < 250; i++) {                       // > one 200-row page
            UUID id = UUID.randomUUID();
            remaining.add(outcome(id));
            when(events.findActive(id)).thenReturn(Optional.of(event(id, now.minus(5, ChronoUnit.DAYS))));
        }
        serve(outcomes, remaining);
        doAnswer(inv -> {                                     // finalizing drops the row from the set
            remaining.removeIf(o -> o.getEventId().equals(inv.getArgument(0, EventOutcome.class).getEventId()));
            return null;
        }).when(service).finalize(any(), any(), any());

        new EventOutcomeFinalizeJob(outcomes, events, service, new PredictorProperties(), clock).run();

        verify(service, times(250)).finalize(any(), any(), eq(now));
    }

    @Test
    void oneStuckRowDoesNotBlockTheRestOfTheBacklog() {
        EventOutcomeRepository outcomes = mock(EventOutcomeRepository.class);
        EventRepository events = mock(EventRepository.class);
        EventOutcomeService service = mock(EventOutcomeService.class);

        List<EventOutcome> remaining = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            UUID id = UUID.randomUUID();
            remaining.add(outcome(id));
            when(events.findActive(id)).thenReturn(Optional.of(event(id, now.minus(5, ChronoUnit.DAYS))));
        }
        UUID stuck = remaining.get(0).getEventId();           // permanently fails, never leaves the set
        serve(outcomes, remaining);
        doAnswer(inv -> {
            EventOutcome o = inv.getArgument(0, EventOutcome.class);
            if (o.getEventId().equals(stuck)) throw new IllegalStateException("boom");
            remaining.removeIf(r -> r.getEventId().equals(o.getEventId()));
            return null;
        }).when(service).finalize(any(), any(), any());

        new EventOutcomeFinalizeJob(outcomes, events, service, new PredictorProperties(), clock).run();

        verify(service, times(250)).finalize(any(), any(), eq(now)); // 249 done + the one failure
        verify(service, times(1)).finalize(any(), argThat(e -> e.getId().equals(stuck)), any());
    }

    /**
     * predictor-edge-10: the job re-reads through {@code findActive} (soft-delete filtered) and
     * re-checks CANCELLED itself, so a cancelled or deleted event is never finalized into the
     * cross-org comparable corpus even if the due query were to hand one back.
     */
    @Test
    void neverFinalizesCancelledOrSoftDeletedEvents() {
        EventOutcomeRepository outcomes = mock(EventOutcomeRepository.class);
        EventRepository events = mock(EventRepository.class);
        EventOutcomeService service = mock(EventOutcomeService.class);

        UUID due = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();   // findActive filters it out -> empty Optional

        serve(outcomes, new ArrayList<>(List.of(outcome(due), outcome(cancelled), outcome(deleted))));
        when(events.findActive(due)).thenReturn(Optional.of(event(due, now.minus(5, ChronoUnit.DAYS))));
        when(events.findActive(cancelled))
                .thenReturn(Optional.of(cancelledEvent(cancelled, now.minus(5, ChronoUnit.DAYS))));
        when(events.findActive(deleted)).thenReturn(Optional.empty());

        new EventOutcomeFinalizeJob(outcomes, events, service, new PredictorProperties(), clock).run();

        verify(service, times(1)).finalize(any(), argThat(e -> e.getId().equals(due)), eq(now));
        verify(service, never()).finalize(any(), argThat(e -> e.getId().equals(cancelled)), any());
        verify(events, never()).findById(any());
    }

    @Test
    void finalizesOnlyEventsEndedBeyondGrace() {
        EventOutcomeRepository outcomes = mock(EventOutcomeRepository.class);
        EventRepository events = mock(EventRepository.class);
        EventOutcomeService service = mock(EventOutcomeService.class);
        PredictorProperties props = new PredictorProperties(); // grace = 3 days

        UUID due = UUID.randomUUID();       // ended 5 days ago -> finalize
        UUID tooRecent = UUID.randomUUID(); // ended 1 day ago -> within grace, skip
        UUID noEnd = UUID.randomUUID();     // null endsAt -> skip

        serve(outcomes, new ArrayList<>(List.of(outcome(due), outcome(tooRecent), outcome(noEnd))));
        when(events.findActive(due)).thenReturn(Optional.of(event(due, now.minus(5, ChronoUnit.DAYS))));
        when(events.findActive(tooRecent)).thenReturn(Optional.of(event(tooRecent, now.minus(1, ChronoUnit.DAYS))));
        when(events.findActive(noEnd)).thenReturn(Optional.of(event(noEnd, null)));

        new EventOutcomeFinalizeJob(outcomes, events, service, props, clock).run();

        verify(service, times(1)).finalize(any(), argThat(e -> e.getId().equals(due)), eq(now));
        verify(service, never()).finalize(any(), argThat(e -> e.getId().equals(tooRecent)), any());
        verify(service, never()).finalize(any(), argThat(e -> e.getId().equals(noEnd)), any());
    }
}
