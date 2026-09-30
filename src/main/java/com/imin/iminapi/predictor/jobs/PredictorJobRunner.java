package com.imin.iminapi.predictor.jobs;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.model.PredictorJob;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Polls {@code predictor_job} and runs due jobs. No ShedLock: the conditional claim UPDATE is the
 * guard, so replicas may poll together and each job still runs once per lease.
 */
@Component
public class PredictorJobRunner {

    private static final Logger log = LoggerFactory.getLogger(PredictorJobRunner.class);

    // ponytail: 5 jobs per tick, run sequentially on the shared scheduler thread; a long handler needs its own executor.
    static final int BATCH = 5;

    private final PredictorJobService service;
    private final PredictorJobRepository repo;
    private final Map<String, PredictorJobHandler> handlers;
    private final PredictorProperties props;

    @Autowired
    public PredictorJobRunner(PredictorJobService service, PredictorJobRepository repo,
                              ObjectProvider<PredictorJobHandler> handlers, PredictorProperties props) {
        this(service, repo, handlers.orderedStream().toList(), props);
    }

    /** Takes the handlers directly; used by tests. */
    public PredictorJobRunner(PredictorJobService service, PredictorJobRepository repo,
                              List<PredictorJobHandler> handlers, PredictorProperties props) {
        this.service = service;
        this.repo = repo;
        this.props = props;
        Map<String, PredictorJobHandler> byKind = new HashMap<>();
        for (PredictorJobHandler h : handlers) {
            PredictorJobHandler previous = byKind.put(h.kind(), h);
            if (previous != null) {
                throw new IllegalStateException("two predictor job handlers for kind " + h.kind() + ": "
                        + previous.getClass().getName() + ", " + h.getClass().getName());
            }
        }
        this.handlers = Map.copyOf(byKind);
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 30_000)
    public void poll() {
        if (!props.isJobsPollEnabled()) return;
        try {
            tick();
        } catch (Exception e) {
            log.error("PredictorJobRunner tick failed: {}: {}",
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    /** One pass: requeue expired leases, then claim and run up to {@link #BATCH} due jobs. */
    public void tick() {
        service.requeueExpired();
        List<UUID> ids = repo.findClaimable(service.now(), PageRequest.of(0, BATCH));
        for (UUID id : ids) {
            // The claim commits before the handler runs, so another runner sees the job as taken.
            Optional<Instant> claimed = service.claim(id);
            if (claimed.isEmpty()) continue;
            Instant lease = claimed.get();
            PredictorJob job = repo.findById(id).orElse(null);
            if (job == null) continue;
            if (!runOne(job, lease)) {
                log.warn("PredictorJobRunner: lease lost for job {} ({}), another runner owns it now",
                        id, job.getKind());
            }
        }
    }

    private boolean runOne(PredictorJob job, Instant lease) {
        PredictorJobHandler handler = handlers.get(job.getKind());
        if (handler == null) {
            // During a rolling deploy an older instance may claim a kind only the new build knows.
            String error = PredictorJobService.truncate("unknown kind: " + job.getKind());
            boolean finished = service.markReleasedOrFailed(job.getId(), lease, job.getAttempts(), error);
            if (!finished) return false;
            if (terminal(job)) logFailed(job, error);
            else log.warn("PredictorJobRunner: no handler for kind {} (job {}), released", job.getKind(), job.getId());
            return true;
        }
        try {
            handler.run(job);
        } catch (Exception e) {
            String error = PredictorJobService.truncate(
                    e.getClass().getSimpleName() + ": " + LogSafe.redact(e.getMessage()));
            // The throwable carries the stack trace; the message text itself stays redacted.
            log.warn("PredictorJobRunner: job {} ({}) attempt {} failed: {}",
                    job.getId(), job.getKind(), job.getAttempts(), error, e);
            try {
                boolean finished = service.markRetryOrFailed(job.getId(), lease, job.getAttempts(), error);
                if (terminal(job) && finished) logFailed(job, error);
                return finished;
            } catch (Exception inner) {
                log.error("PredictorJobRunner: could not record failure of job {} ({}): {}: {}; original: {}",
                        job.getId(), job.getKind(), inner.getClass().getSimpleName(),
                        LogSafe.redact(inner.getMessage()), error);
                return true;
            }
        }
        return service.markDone(job.getId(), lease);
    }

    private static boolean terminal(PredictorJob job) {
        return job.getAttempts() >= PredictorJobService.MAX_ATTEMPTS;
    }

    private static void logFailed(PredictorJob job, String error) {
        log.error("PredictorJobRunner: job {} ({}) failed after {} attempts: {}",
                job.getId(), job.getKind(), job.getAttempts(), error);
    }
}
