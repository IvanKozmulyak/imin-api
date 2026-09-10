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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Post-event outcome finalize (spec §6.1). Daily at 04:30 UTC it fills the post-event
 * fields of every outcome row whose event ended more than {@code finalizeGraceDays} ago
 * (the grace lets late refunds settle before the result is frozen). ShedLock-guarded so
 * only one replica runs the pass.
 *
 * <p>It pages through the WHOLE due set, {@value #PAGE} rows at a time, off a query that
 * carries the due predicate itself — never a single unordered page filtered afterwards in
 * Java. Every published event has a not-yet-finalized row from the moment it publishes, so
 * a Java-side filter lets live and future events fill the page permanently and the corpus
 * silently stops growing.
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
        // Page until the candidate set is exhausted. The query returns only rows that are due,
        // in a stable order, and a finalized row leaves the set — so re-reading the head of the
        // set is the next page. Rows that FAIL stay in the set, so they are remembered here and
        // skipped on the next read: one stuck row can never re-serve the same page and starve
        // the backlog behind it (it is retried on the next daily pass, with its error logged).
        Set<UUID> attempted = new HashSet<>();
        int finalized = 0;
        int failed = 0;
        List<EventOutcome> batch;
        boolean progressed;
        do {
            batch = outcomes.findDueForFinalize(cutoff, PageRequest.of(0, PAGE));
            progressed = false;
            for (EventOutcome o : batch) {
                if (!attempted.add(o.getEventId())) continue;
                progressed = true;
                Event e = events.findById(o.getEventId()).orElse(null);
                // Belt and braces: the query already excludes these, but never finalize an event
                // that has not ended (or has vanished) on the strength of the query alone.
                if (e == null || e.getEndsAt() == null || !e.getEndsAt().isBefore(cutoff)) {
                    failed++;
                    continue;
                }
                try {
                    service.finalize(o, e, now);
                    finalized++;
                } catch (Exception ex) {
                    failed++;
                    log.error("EventOutcomeFinalizeJob: finalize failed for event={}: {}", o.getEventId(), ex.getMessage(), ex);
                }
            }
            if (batch.size() == PAGE) {
                log.info("EventOutcomeFinalizeJob: a full page of {} due outcome(s) — backlog, continuing", PAGE);
            }
        } while (progressed && batch.size() == PAGE);
        if (failed > 0) {
            log.warn("EventOutcomeFinalizeJob: {} outcome(s) could not be finalized this pass", failed);
        }
        if (finalized > 0) {
            log.info("EventOutcomeFinalizeJob: finalized {} event outcome(s)", finalized);
        } else {
            log.debug("EventOutcomeFinalizeJob: nothing due");
        }
    }
}
