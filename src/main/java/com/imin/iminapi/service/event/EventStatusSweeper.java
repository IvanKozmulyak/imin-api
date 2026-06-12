package com.imin.iminapi.service.event;

import com.imin.iminapi.repository.EventRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Transitions LIVE events to PAST once their {@code endsAt} has passed.
 * This is the authoritative mechanism that closes out events — without this
 * sweeper an event would remain LIVE in API listings and on the public buyer
 * site even after it has concluded.
 *
 * <p>The sweeper runs <strong>twice per day</strong>: at 00:00 UTC (midnight)
 * and at 12:00 UTC (noon). Consequently an ended event may remain LIVE for up
 * to ~12 hours until the next tick. This cadence is intentional — the job is
 * inexpensive and event-end times are not second-precise from the product
 * owner's perspective.
 *
 * <p>Events with a {@code null endsAt} have no defined end time and are
 * intentionally left LIVE — the sweeper never touches them.
 *
 * <p>The tick performs a single bulk JPQL UPDATE (one indexed statement) so
 * it is O(log n) on the {@code status + ends_at} path regardless of how many
 * LIVE events exist. DRAFT, CANCELLED, and already-PAST events are never
 * touched by the WHERE clause.
 *
 * <p>{@link SchedulerLock} serializes the tick across replicas: only one
 * instance acquires the lock per cycle, so a multi-replica deploy never runs
 * two sweeps at the same time.
 */
@Component
public class EventStatusSweeper {

    private static final Logger log = LoggerFactory.getLogger(EventStatusSweeper.class);

    private final EventRepository events;
    private final Clock clock;

    public EventStatusSweeper(EventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * One sweeper tick. Runs at 00:00 UTC and 12:00 UTC every day (cron
     * {@code "0 0 0,12 * * *"}, server TZ = UTC on Railway). An ended event
     * may stay LIVE for up to ~12 h until the next scheduled tick.
     */
    @Scheduled(cron = "0 0 0,12 * * *")
    @SchedulerLock(name = "EventStatusSweeper.sweep", lockAtLeastFor = "PT10S", lockAtMostFor = "PT30M")
    public void sweep() {
        Instant now = clock.instant();
        int count = events.markLivePast(now, now);
        if (count > 0) {
            log.info("EventStatusSweeper: marked {} LIVE event(s) PAST", count);
        } else {
            log.debug("EventStatusSweeper: no events to transition");
        }
    }
}
