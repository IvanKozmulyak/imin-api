package com.imin.iminapi.predictor.sources.prim;

import com.imin.iminapi.predictor.model.TransitSyncState;
import com.imin.iminapi.predictor.repository.TransitSyncStateRepository;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.prim.IdfmStopsClient.Outcome;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Status;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executor;

/**
 * Downloads the IDFM stops reference weekly, and at boot until a sync succeeds, only while the {@code prim} gate is
 * on. A sync that is not ok keeps the stored stops, so near-venue findings never go dark on a failed download.
 */
@Component
public class IdfmStopsJob {

    private static final Logger log = LoggerFactory.getLogger(IdfmStopsJob.class);
    static final String GATE = "prim";

    private final IdfmStopsClient client;
    private final TransitStopStore store;
    private final TransitSyncStateRepository states;
    private final SourceGates gates;
    private final Clock clock;
    /** This bean through its proxy, so the startup run takes the ShedLock too. */
    private final ObjectProvider<IdfmStopsJob> self;
    private final Executor executor;

    public IdfmStopsJob(IdfmStopsClient client, TransitStopStore store, TransitSyncStateRepository states,
                        SourceGates gates, Clock clock, ObjectProvider<IdfmStopsJob> self,
                        @Qualifier("primSyncExecutor") Executor executor) {
        this.client = client;
        this.store = store;
        this.states = states;
        this.gates = gates;
        this.clock = clock;
        this.self = self;
        this.executor = executor;
    }

    /** Off the boot thread on the PRIM seed's executor; a rejected or failed seed is left to the weekly cron. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            if (!gates.isOn(GATE) || states.findById(TransitSyncState.IDFM_STOPS)
                    .map(TransitSyncState::getSyncedAt).isPresent()) return;
            executor.execute(() -> {
                try {
                    self.getObject().run();
                } catch (Exception e) {
                    log.warn("IdfmStopsJob startup run failed (the weekly cron retries): {}", e.getClass().getSimpleName());
                }
            });
        } catch (Exception e) {
            log.warn("IdfmStopsJob startup check failed (the weekly cron retries): {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "0 45 4 * * MON", zone = "Europe/Paris")
    @SchedulerLock(name = "idfm_stops_sync", lockAtMostFor = "PT10M")
    public void run() {
        if (!gates.isOn(GATE)) return;
        Instant now = clock.instant();
        Outcome outcome = client.fetch();
        if (outcome.status() == Status.OK) {
            int stored;
            try {
                stored = store.replace(outcome.stops(), now);
            } catch (RuntimeException e) {
                log.error("IdfmStopsJob: storing the stops failed; the previous stops are kept", e);
                try {
                    store.recordAttempt(Status.FAILED, now);
                } catch (RuntimeException recordFailure) {
                    log.warn("IdfmStopsJob: recording the failed attempt failed too: {}",
                            recordFailure.getClass().getSimpleName());
                }
                return;
            }
            log.info("IdfmStopsJob: stored {} stops and zones", stored);
            return;
        }
        store.recordAttempt(outcome.status(), now);
        // the sync is weekly, so every failure pages
        log.error("IdfmStopsJob: sync {}; the previous stops are kept", outcome.status().wire(),
                new IllegalStateException("IDFM stops sync not ok"));
    }
}
