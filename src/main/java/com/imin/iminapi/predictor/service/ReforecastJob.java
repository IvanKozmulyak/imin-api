package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.model.ReforecastTrigger;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.service.event.EventSalesTotals;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Daily live re-forecast cron (spec §4.2, task 86cav479j): 06:00 Europe/Amsterdam, ShedLock, over
 * every live, published, future, on-sale event that has sold live-mode tickets. Each recompute is idempotent
 * and cheap (Stage 1 arithmetic), so a full daily pass is inexpensive at early-platform scale.
 * Runs AFTER {@code PacingCurveJob} (05:30) so it reads fresh curves.
 */
@Component
public class ReforecastJob {

    private static final Logger log = LoggerFactory.getLogger(ReforecastJob.class);

    private final EventRepository events;
    private final EventSalesTotals salesTotals;
    private final ReforecastService reforecast;
    private final Clock clock;

    public ReforecastJob(EventRepository events, EventSalesTotals salesTotals,
                         ReforecastService reforecast, Clock clock) {
        this.events = events;
        this.salesTotals = salesTotals;
        this.reforecast = reforecast;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Europe/Amsterdam")
    @SchedulerLock(name = "predictor_reforecast_daily", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        int done = recomputeWithLiveSales(events.findMomentumCandidates(clock.instant()));
        log.info("ReforecastJob: re-forecast {} live event(s) with sales", done);
    }

    /** The pass body over the given candidates: recomputes those with live sales, returns how many. */
    public int recomputeWithLiveSales(List<Event> candidates) {
        int done = 0;
        for (Event e : candidates) {
            if (salesTotals.soldForEvent(e.getId()) <= 0) continue; // "with live sales"
            try {
                reforecast.recompute(e.getId(), ReforecastTrigger.SCHEDULED);
                done++;
            } catch (Exception ex) {
                log.error("ReforecastJob: recompute failed for event {}: {}", e.getId(), ex.getMessage(), ex);
            }
        }
        return done;
    }
}
