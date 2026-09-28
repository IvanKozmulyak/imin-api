package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.engine.OutcomeMath;
import com.imin.iminapi.audienceplan.engine.OutcomeMath.Phase;
import com.imin.iminapi.audienceplan.repository.OutcomeStore;
import com.imin.iminapi.audienceplan.repository.OutcomeStore.EventRef;
import com.imin.iminapi.audienceplan.repository.OutcomeStore.Experiment;
import com.imin.iminapi.audienceplan.repository.OutcomeStore.Tally;
import com.imin.iminapi.util.LogSafe;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Daily after-event collection: per experiment arm, who bought, attended, unsubscribed or complained, stored as
 * aggregates at D+1 and overwritten at D+7; then the calibration is rebuilt. Rerunning a day changes nothing.
 */
@Component
public class OutcomeCollector {

    private static final Logger log = LoggerFactory.getLogger(OutcomeCollector.class);

    /** ponytail: an event that started longer ago than this and was never collected stays pending. */
    static final Duration LOOKBACK = Duration.ofDays(40);

    /** {@code calibrated} is false when the calibration rebuild failed. */
    public record Result(int seen, int written, int unchanged, int skipped, int failed, boolean calibrated) {}

    /** One arm-level computation; nothing here is written. */
    public record Computed(List<Experiment> experiments, Map<UUID, Tally> tallies, Integer newGuests) {}

    private final OutcomeStore store;
    private final CalibrationService calibration;
    private final AudiencePlanAccess access;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Calls {@link #run()} through the proxy so its scheduler lock applies. */
    private final ObjectProvider<OutcomeCollector> self;

    public OutcomeCollector(OutcomeStore store, CalibrationService calibration, AudiencePlanAccess access,
                            PlatformTransactionManager txManager, Clock clock, ObjectProvider<OutcomeCollector> self) {
        this.store = store;
        this.calibration = calibration;
        this.access = access;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.self = self;
    }

    /** Before the 09:00 plan refresh, so plans recompute against the new calibration the same morning. */
    @Scheduled(cron = "0 0 8 * * *", zone = "Europe/Paris")
    public void scheduled() {
        try {
            self.getObject().run();
        } catch (Exception e) {
            log.error("OutcomeCollector: run failed (tomorrow retries): {} {}",
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    /** Each event in its own transaction; one failure never stops the pass. */
    @SchedulerLock(name = "audience_outcomes", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public Result run() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int seen = 0;
        int written = 0;
        int unchanged = 0;
        int skipped = 0;
        int failed = 0;
        for (EventRef e : store.eventsWithExperiments(now.minus(LOOKBACK), now)) {
            seen++;
            if (!access.isEnabled(e.orgId())) {
                skipped++;
                continue;
            }
            Instant doorClose = OutcomeMath.doorClose(e.startsAt(), e.endsAt());
            Optional<Phase> due = OutcomeMath.due(doorClose, now);
            if (due.isEmpty()) {
                skipped++;
                continue;
            }
            Phase phase = due.get();
            try {
                Optional<OutcomeStore.StoredEvent> stored = store.storedEvent(e.orgId(), e.eventId());
                if (stored.isPresent() && (stored.get().phase().equals(phase.key())
                        || stored.get().phase().equals(Phase.D7.key()))) {
                    unchanged++;
                    continue;
                }
                tx.executeWithoutResult(s -> write(e, phase, doorClose, now));
                written++;
            } catch (Exception ex) {
                failed++;
                log.warn("OutcomeCollector: event {} skipped: {} {}", e.eventId(), ex.getClass().getSimpleName(),
                        LogSafe.redact(ex.getMessage()));
            }
        }
        // Rebuilt on every run, so a failed rebuild is repaired the next day even when no event is due.
        boolean calibrated = false;
        try {
            tx.executeWithoutResult(s -> calibration.rebuild());
            calibrated = true;
        } catch (Exception ex) {
            log.warn("OutcomeCollector: calibration rebuild failed (tomorrow retries): {} {}",
                    ex.getClass().getSimpleName(), LogSafe.redact(ex.getMessage()));
        }
        calibration.invalidate();
        Result result = new Result(seen, written, unchanged, skipped, failed, calibrated);
        log.info("OutcomeCollector: done, {} events, {} written, {} unchanged, {} skipped, {} failed", seen, written,
                unchanged, skipped, failed);
        return result;
    }

    /**
     * Counts every arm of an event: orders until {@code doorClose}, unsubscribes and complaints until {@code until}.
     * {@code newGuests} is null when no assignment is left to date the first invitation.
     */
    public Computed compute(UUID orgId, UUID eventId, Instant doorClose, Instant until) {
        List<Experiment> experiments = store.experiments(orgId, eventId);
        Map<UUID, Tally> tallies = store.tallies(orgId, eventId, doorClose, until);
        Integer newGuests = store.firstAssignedAt(orgId, eventId)
                .map(first -> store.newGuests(orgId, eventId, first, doorClose))
                .orElse(null);
        return new Computed(experiments, tallies, newGuests);
    }

    private void write(EventRef e, Phase phase, Instant doorClose, Instant now) {
        Computed c = compute(e.orgId(), e.eventId(), doorClose, doorClose.plus(phase.window()));
        store.replaceOutcome(e.orgId(), e.eventId(), phase.key(), c.experiments(), c.tallies(), c.newGuests(),
                doorClose, now);
    }
}
