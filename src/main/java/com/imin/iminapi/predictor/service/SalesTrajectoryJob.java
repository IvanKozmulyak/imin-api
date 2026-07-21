package com.imin.iminapi.predictor.service;

import com.imin.iminapi.repository.TicketRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Materializes the daily sales trajectory (spec §6.2) for every event that has sold
 * tickets. Daily at 03:45 UTC, ShedLock-guarded. The FIRST run backfills all history
 * (every past event with sales) because {@link SalesTrajectoryService#materialize} is a
 * full recompute-and-replace per event — subsequent runs simply refresh the same rows.
 * Idempotent by construction; NO new write path in checkout.
 *
 * <p>Scope note: this recomputes for ALL events with sold tickets each run. For a young
 * platform that is cheap and keeps the code honest (backfill and refresh are the same
 * path). At larger scale this would be narrowed to LIVE + recently-ended events; the
 * recompute-per-event unit does not change.
 */
@Component
public class SalesTrajectoryJob {

    private static final Logger log = LoggerFactory.getLogger(SalesTrajectoryJob.class);

    private final TicketRepository tickets;
    private final SalesTrajectoryService service;

    public SalesTrajectoryJob(TicketRepository tickets, SalesTrajectoryService service) {
        this.tickets = tickets;
        this.service = service;
    }

    @Scheduled(cron = "0 45 3 * * *")
    @SchedulerLock(name = "predictor_sales_trajectory", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        List<UUID> eventIds = tickets.findDistinctEventIdsWithSoldTickets();
        int done = 0;
        for (UUID eventId : eventIds) {
            try {
                service.materialize(eventId);
                done++;
            } catch (Exception ex) {
                log.error("SalesTrajectoryJob: materialize failed for event={}: {}", eventId, ex.getMessage(), ex);
            }
        }
        log.info("SalesTrajectoryJob: materialized trajectory for {} event(s)", done);
    }
}
