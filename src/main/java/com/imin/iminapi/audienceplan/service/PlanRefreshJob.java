package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.PlanRefreshExecutor;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.service.PredictorReactivityEvents;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.util.LogSafe;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.util.UUID;

/**
 * Keeps stored audience plans current without the organizer opening the card: one plan when an event is published,
 * and a daily pass over on-sale upcoming events. A new row is written only when the plan's inputs changed.
 */
@Component
public class PlanRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(PlanRefreshJob.class);

    /** Counts of one daily pass. */
    public record Result(int events, int created, int unchanged, int skipped, int failed) {}

    private final PlanService plans;
    private final EventRepository events;
    private final Clock clock;
    private final InviteOnPublishService invites;
    /** Calls {@link #run()} through the proxy so its scheduler lock applies. */
    private final ObjectProvider<PlanRefreshJob> self;

    public PlanRefreshJob(PlanService plans, EventRepository events, Clock clock, ObjectProvider<PlanRefreshJob> self,
                          InviteOnPublishService invites) {
        this.plans = plans;
        this.events = events;
        this.clock = clock;
        this.self = self;
        this.invites = invites;
    }

    /**
     * After the publish commits, off the request thread: refresh the plan, then run the invitations stored for the
     * publish against it. Each step is caught on its own; a failure is logged and never reaches the publish.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(PlanRefreshExecutor.NAME)
    public void onEventPublished(PredictorReactivityEvents.EventPublished published) {
        try {
            PlanService.Refresh r = plans.refresh(published.eventId());
            log.info("PlanRefreshJob: publish of event {} -> plan {}", published.eventId(), r);
        } catch (Exception e) {
            log.warn("PlanRefreshJob: plan refresh on publish failed for event {}: {} {}", published.eventId(),
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
        try {
            invites.runOnPublish(published.eventId());
        } catch (Exception e) {
            log.warn("PlanRefreshJob: invitations on publish failed for event {}: {} {}", published.eventId(),
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Europe/Paris")
    public void scheduled() {
        try {
            self.getObject().run();
        } catch (Exception e) {
            log.error("PlanRefreshJob: daily run failed (tomorrow retries): {} {}",
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    /**
     * Each event in its own transaction; one failure never stops the pass. Today's date is a hashed input, so an
     * on-sale event gets at most one new plan a day. ponytail: superseded rows are kept (no pruning yet).
     */
    @SchedulerLock(name = "audience_plan_refresh", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public Result run() {
        int seen = 0;
        int created = 0;
        int unchanged = 0;
        int skipped = 0;
        int failed = 0;
        for (Event e : events.findMomentumCandidates(clock.instant())) {
            seen++;
            UUID eventId = e.getId();
            try {
                switch (plans.refresh(eventId)) {
                    case CREATED -> created++;
                    case UNCHANGED -> unchanged++;
                    case SKIPPED -> skipped++;
                }
            } catch (Exception ex) {
                failed++;
                log.warn("PlanRefreshJob: event {} skipped: {} {}", eventId, ex.getClass().getSimpleName(),
                        LogSafe.redact(ex.getMessage()));
            }
        }
        Result result = new Result(seen, created, unchanged, skipped, failed);
        log.info("PlanRefreshJob: done, {} on-sale events, {} new plans, {} unchanged, {} skipped, {} failed",
                seen, created, unchanged, skipped, failed);
        return result;
    }
}
