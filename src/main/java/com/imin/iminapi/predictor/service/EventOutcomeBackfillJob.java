package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.repository.EventRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-shot retro-backfill of the outcome record (spec §6.1). Pages through every
 * published event and reconstructs a frozen snapshot for any that has no
 * {@code event_outcomes} row yet — closing the gap for events that were published
 * before this table existed. Reconstructed rows are flagged
 * {@code snapshotReconstructed=true} so the corpus can weigh them honestly.
 *
 * <p>Post-event finalization is left to {@link EventOutcomeFinalizeJob}: a reconstructed
 * row for an already-ended event has {@code finalizedAt=null}, so the next finalize tick
 * fills its result.
 *
 * <p>Runs daily at 03:15 UTC and is fully idempotent (existing rows are never touched);
 * once prod is caught up the cron can be disabled. ShedLock serializes it across replicas.
 */
@Component
public class EventOutcomeBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(EventOutcomeBackfillJob.class);

    private final EventRepository events;
    private final EventOutcomeService service;
    private final PredictorProperties props;

    public EventOutcomeBackfillJob(EventRepository events, EventOutcomeService service, PredictorProperties props) {
        this.events = events;
        this.service = service;
        this.props = props;
    }

    @Scheduled(cron = "0 15 3 * * *")
    @SchedulerLock(name = "predictor_outcome_backfill", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        int page = 0;
        int reconstructed = 0;
        List<Event> batch;
        do {
            batch = events.findAllPublished(PageRequest.of(page, props.getBackfillPageSize()));
            for (Event e : batch) {
                try {
                    if (service.reconstructIfAbsent(e)) reconstructed++;
                } catch (Exception ex) {
                    log.error("EventOutcomeBackfillJob: reconstruct failed for event={}: {}", e.getId(), ex.getMessage(), ex);
                }
            }
            page++;
        } while (batch.size() == props.getBackfillPageSize());
        if (reconstructed > 0) {
            log.info("EventOutcomeBackfillJob: reconstructed {} outcome snapshot(s)", reconstructed);
        } else {
            log.debug("EventOutcomeBackfillJob: nothing to backfill");
        }
    }
}
