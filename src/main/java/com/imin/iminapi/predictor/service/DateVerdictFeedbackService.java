package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.predictor.dto.DateVerdictFeedbackDto;
import com.imin.iminapi.predictor.dto.EventDateCheckDto;
import com.imin.iminapi.predictor.dto.PredictionFeedbackRequest;
import com.imin.iminapi.predictor.model.DateVerdictAnswer;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The after-event question "did the date verdict match?": one answer per event, upserted. Asked only for a PAST
 * event; the first answer also needs the event's current, non-stale date check with a real verdict, whose
 * check id, night and verdict are fixed as the snapshot. Later answers replace answer and comment only.
 * The comment is organizer free text and is never logged.
 */
@Service
public class DateVerdictFeedbackService {

    /** Verdicts an organizer can agree or disagree with; not_enough_data is not one. */
    static final Set<String> RATED_VERDICTS = Set.of("good", "adjust", "move");

    private final DateVerdictFeedbackStore store;
    private final DateCheckService dateChecks;
    private final Clock clock;

    public DateVerdictFeedbackService(DateVerdictFeedbackStore store, DateCheckService dateChecks, Clock clock) {
        this.store = store;
        this.dateChecks = dateChecks;
        this.clock = clock;
    }

    @Transactional
    public void record(Event e, PredictionFeedbackRequest req) {
        DateVerdictAnswer answer = parseAnswer(req.answer());
        if (e.getStatus() != EventStatus.PAST) {
            throw ApiException.invalidState("The event has not ended");
        }
        String comment = req.comment() == null || req.comment().isBlank() ? null : req.comment().trim();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (store.find(e.getId()).isEmpty()) {
            EventDateCheckDto dc = dateChecks.currentForEvent(e)
                    .filter(c -> !c.stale())
                    .filter(c -> c.result() != null && RATED_VERDICTS.contains(c.result().verdict()))
                    .orElseThrow(() -> ApiException.invalidState("No date verdict to rate for this event"));
            store.insertIfAbsent(UUID.randomUUID(), e.getId(), dc.id(), dc.forDate(), dc.result().verdict(),
                    answer.wire(), comment, now, now);
        }
        // A concurrent first answer's insert is a no-op; both updates run, so the last write wins on one row.
        store.update(e.getId(), answer.wire(), comment, now);
    }

    /** The stored answer, only once the event has ended. */
    @Transactional(readOnly = true)
    public Optional<DateVerdictFeedbackDto> forEvent(Event e) {
        if (e.getStatus() != EventStatus.PAST || e.getId() == null) return Optional.empty();
        return store.find(e.getId()).map(r -> new DateVerdictFeedbackDto(r.answer(), r.comment(), r.answeredAt(),
                r.dateCheckId(), r.forDate(), r.verdict()));
    }

    private static DateVerdictAnswer parseAnswer(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "answer is required for date_verdict_match", Map.of("answer", "required"));
        }
        try {
            return DateVerdictAnswer.fromWire(raw);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "answer must be one of: yes, partly, no", Map.of("answer", "invalid"));
        }
    }
}
