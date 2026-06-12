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
 * Periodically transitions LIVE events to PAST once their {@code endsAt} has
 * passed. This is the authoritative mechanism that closes out events whose end
 * time has been reached — without this sweeper, an event would remain LIVE in
 * API listings and on the public buyer site even after it has concluded.
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
     * One sweeper tick. {@code fixedDelay=60_000} schedules the next run 60 s
     * AFTER the current one finishes; {@code initialDelay=30_000} gives the
     * application 30 s after startup to warm up before the first sweep.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @SchedulerLock(name = "EventStatusSweeper.sweep", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
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
