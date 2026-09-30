package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorSegmentStatusRepository;
import com.imin.iminapi.predictor.service.CalibrationViewService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The calibration panel over a ledger that holds date-check renders, which have no event. */
class CalibrationViewServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    void renderSkipsJoinedRowWithoutEvent() {
        PredictionLedgerRepository ledger = mock(PredictionLedgerRepository.class);
        EventOutcomeRepository outcomes = mock(EventOutcomeRepository.class);
        PredictorSegmentStatusRepository segments = mock(PredictorSegmentStatusRepository.class);

        UUID eventId = UUID.randomUUID();
        PredictionLedger dateCheckRender = row(null, PredictionSurface.DATE_CHECK);
        PredictionLedger eventRender = row(eventId, PredictionSurface.PRE_PUBLISH);
        when(ledger.findAll()).thenReturn(List.of(dateCheckRender, eventRender));
        // Spring Data rejects a null id; the mock must too, or the guard is untested.
        when(outcomes.findById(isNull())).thenThrow(new IllegalArgumentException("The given id must not be null"));
        EventOutcome o = new EventOutcome();
        o.setEventId(eventId);
        when(outcomes.findById(eventId)).thenReturn(Optional.of(o));
        when(segments.findAll()).thenReturn(List.of());

        String html = new CalibrationViewService(ledger, outcomes, segments).render();

        verify(outcomes, never()).findById(isNull());
        assertThat(html).contains("renders: 2").contains("outcome-joined: 1").contains("scored (parseable + outcome): 1");
    }

    private static PredictionLedger row(UUID eventId, PredictionSurface surface) {
        PredictionLedger r = new PredictionLedger();
        r.setId(UUID.randomUUID());
        r.setEventId(eventId);
        r.setSurface(surface);
        r.setOutcomeJoinedAt(NOW);
        r.setOutputJson("{}");
        return r;
    }
}
