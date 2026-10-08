package com.imin.iminapi.predictor.sources.prim;

import com.imin.iminapi.predictor.model.TransitSyncState;
import com.imin.iminapi.predictor.repository.TransitSyncStateRepository;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Disruption;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Outcome;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/**
 * Polls PRIM every 30 minutes, and at boot until a poll succeeds, only while the {@code prim} gate is on.
 * A poll that is not ok keeps the stored rows, so a failure never makes a night clear.
 */
@Component
public class PrimSyncJob {

    private static final Logger log = LoggerFactory.getLogger(PrimSyncJob.class);
    static final String GATE = "prim";

    private final PrimDisruptionsClient client;
    private final PrimWriter writer;
    private final TransitSyncStateRepository states;
    private final SourceGates gates;
    private final PrimProperties props;
    private final Clock clock;
    /** This bean through its proxy, so the startup run takes the ShedLock too. */
    private final ObjectProvider<PrimSyncJob> self;
    private final Executor executor;

    public PrimSyncJob(PrimDisruptionsClient client, PrimWriter writer, TransitSyncStateRepository states,
                       SourceGates gates, PrimProperties props, DataSourceCatalog catalog, Clock clock,
                       ObjectProvider<PrimSyncJob> self, @Qualifier("primSyncExecutor") Executor executor) {
        this.client = client;
        this.writer = writer;
        this.states = states;
        this.gates = gates;
        this.props = props;
        this.clock = clock;
        this.self = self;
        this.executor = executor;
        if (catalog.byId(TransitSyncState.IDFM_PRIM).isEmpty()
                || !catalog.syncSource(TransitSyncState.IDFM_PRIM).equals(Optional.of(TransitSyncState.IDFM_PRIM))) {
            throw new IllegalStateException("PRIM source needs a sources.yaml entry " + TransitSyncState.IDFM_PRIM
                    + " with syncSource: " + TransitSyncState.IDFM_PRIM);
        }
    }

    /** Off the boot thread: a slow or failing upstream never holds up startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            if (!gates.isOn(GATE) || lastOk().isPresent()) return;
            executor.execute(() -> {
                try {
                    self.getObject().run();
                } catch (Exception e) {
                    log.warn("PrimSyncJob startup run failed (the 30-minute cron retries): {}", e.getClass().getSimpleName());
                }
            });
        } catch (Exception e) {
            log.warn("PrimSyncJob startup check failed (the 30-minute cron retries): {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "0 */30 * * * *", zone = "Europe/Paris")
    @SchedulerLock(name = "prim_disruptions_sync", lockAtMostFor = "PT10M")
    public void run() {
        if (!gates.isOn(GATE)) return;
        Instant now = clock.instant();
        Outcome outcome = client.fetch();
        if (outcome.status() == Status.OK) {
            int stored;
            try {
                stored = writer.replace(outcome.snapshot(), now);
            } catch (RuntimeException e) {
                log.error("PrimSyncJob: storing the snapshot failed; the previous rows are kept", e);
                try {
                    writer.recordAttempt(Status.FAILED, now);
                } catch (RuntimeException recordFailure) {
                    log.warn("PrimSyncJob: recording the failed attempt failed too: {}",
                            recordFailure.getClass().getSimpleName());
                }
                return;
            }
            Map<String, Integer> byKind = new TreeMap<>();
            for (Disruption d : outcome.snapshot().disruptions()) byKind.merge(d.kind(), 1, Integer::sum);
            log.info("PrimSyncJob: stored {} disruptions {}, dropped {}, feed updated {}", stored, byKind,
                    outcome.snapshot().dropped(), outcome.snapshot().feedUpdatedAt());
            // A feed that stays old while polls succeed would read stale to every check without anyone being paged.
            Instant feedAt = outcome.snapshot().feedUpdatedAt();
            if (feedAt != null && feedAt.isBefore(now.minus(Duration.ofHours(props.getMaxAgeHours())))) {
                log.error("PrimSyncJob: feed last updated {}, older than {} h; checks read the source as stale",
                        feedAt, props.getMaxAgeHours(), new IllegalStateException("PRIM feed out of date"));
            }
            return;
        }
        writer.recordAttempt(outcome.status(), now);
        if (outcome.status() == Status.REJECTED_KEY) {
            log.error("PrimSyncJob: IDFM_PRIM_API_KEY rejected (401); stored rows kept",
                    new IllegalStateException("PRIM rejected the API key"));
        } else {
            log.warn("PrimSyncJob: poll {}; stored rows kept", outcome.status().wire());
        }
        Optional<Instant> last = lastOk();
        if (last.isEmpty() || last.get().isBefore(now.minus(Duration.ofHours(props.getMaxAgeHours())))) {
            log.error("PrimSyncJob: no ok poll within {} h (last {}); checks read the source as stale",
                    props.getMaxAgeHours(), last.map(Instant::toString).orElse("never"),
                    new IllegalStateException("PRIM data out of date"));
        }
    }

    private Optional<Instant> lastOk() {
        return states.findById(TransitSyncState.IDFM_PRIM).map(TransitSyncState::getSyncedAt);
    }
}
