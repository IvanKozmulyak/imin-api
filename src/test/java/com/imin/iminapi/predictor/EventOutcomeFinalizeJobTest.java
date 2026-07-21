package com.imin.iminapi.predictor;

import com.imin.iminapi.model.Event;
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

    private EventOutcome outcome(UUID eventId) {
        EventOutcome o = new EventOutcome();
        o.setEventId(eventId);
        return o;
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

        when(outcomes.findByFinalizedAtIsNull(any()))
                .thenReturn(List.of(outcome(due), outcome(tooRecent), outcome(noEnd)));
        when(events.findById(due)).thenReturn(Optional.of(event(due, now.minus(5, ChronoUnit.DAYS))));
        when(events.findById(tooRecent)).thenReturn(Optional.of(event(tooRecent, now.minus(1, ChronoUnit.DAYS))));
        when(events.findById(noEnd)).thenReturn(Optional.of(event(noEnd, null)));

        new EventOutcomeFinalizeJob(outcomes, events, service, props, clock).run();

        verify(service, times(1)).finalize(any(), argThat(e -> e.getId().equals(due)), eq(now));
        verify(service, never()).finalize(any(), argThat(e -> e.getId().equals(tooRecent)), any());
        verify(service, never()).finalize(any(), argThat(e -> e.getId().equals(noEnd)), any());
    }
}
