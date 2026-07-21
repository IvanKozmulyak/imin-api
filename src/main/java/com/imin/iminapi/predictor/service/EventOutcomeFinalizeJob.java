package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.repository.EventRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Post-event outcome finalize (spec §6.1). Daily at 04:30 UTC it finds outcome rows
 * still awaiting their result and fills the post-event fields for any whose event has
 * ended more than {@code finalizeGraceDays} ago (the grace lets late refunds settle
 * before the result is frozen). ShedLock-guarded so only one replica runs the pass.
 *
 * <p>Idempotent: {@link EventOutcomeService#finalize} recomputes from source, and once
 * {@code finalizedAt} is set the row drops out of the candidate query — so a finished
 * event is finalized exactly once under normal operation.
 */
@Component
public class EventOutcomeFinalizeJob {

    private static final Logger log = LoggerFactory.getLogger(EventOutcomeFinalizeJob.class);
    private static final int PAGE = 200;

    private final EventOutcomeRepository outcomes;
    private final EventRepository events;
    private final EventOutcomeService service;
    private final PredictorProperties props;
    private final Clock clock;

    public EventOutcomeFinalizeJob(EventOutcomeRepository outcomes, EventRepository events,
                                   EventOutcomeService service, PredictorProperties props, Clock clock) {
        this.outcomes = outcomes;
        this.events = events;
        this.service = service;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 4 * * *")
    @SchedulerLock(name = "predictor_outcome_finalize", lockAtMostFor = "PT1H", lockAtLeastFor = "PT10S")
    public void run() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(props.getFinalizeGraceDays(), ChronoUnit.DAYS);
        List<EventOutcome> due = outcomes.findByFinalizedAtIsNull(PageRequest.of(0, PAGE));
        int finalized = 0;
        for (EventOutcome o : due) {
            Event e = events.findById(o.getEventId()).orElse(null);
            if (e == null || e.getEndsAt() == null || !e.getEndsAt().isBefore(cutoff)) continue;
            try {
                service.finalize(o, e, now);
                finalized++;
            } catch (Exception ex) {
                log.error("EventOutcomeFinalizeJob: finalize failed for event={}: {}", o.getEventId(), ex.getMessage(), ex);
            }
        }
        if (finalized > 0) {
            log.info("EventOutcomeFinalizeJob: finalized {} event outcome(s)", finalized);
        } else {
            log.debug("EventOutcomeFinalizeJob: nothing due");
        }
    }
}
