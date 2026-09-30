package com.imin.iminapi.predictor.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.imin.iminapi.predictor.model.PredictorJob;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import com.imin.iminapi.predictor.service.PredictorJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/** Enqueue and state transitions of the durable {@code predictor_job} queue. */
@Service
public class PredictorJobService {

    private static final Logger log = LoggerFactory.getLogger(PredictorJobService.class);

    public static final int MAX_ATTEMPTS = 3;
    public static final Duration LOCK = Duration.ofMinutes(10);
    public static final int MAX_ERROR = 2000;
    public static final Duration RELEASE_DELAY = Duration.ofMinutes(5);

    static final String QUEUED = "queued";
    static final String DONE = "done";
    static final String FAILED = "failed";

    private final PredictorJobRepository repo;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PredictorJobService(PredictorJobRepository repo, Clock clock, TransactionTemplate tx) {
        this.repo = repo;
        this.clock = clock;
        this.tx = tx;
    }

    /** Joins the caller's transaction, so the job exists only if the caller's work commits. */
    @Transactional
    public UUID enqueue(String kind, Object payload) {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("job kind is blank");
        String json;
        try {
            json = payload == null ? "{}" : PredictorJson.MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("job payload is not serialisable: " + e.getOriginalMessage(), e);
        }
        Instant now = now();
        PredictorJob job = new PredictorJob();
        job.setKind(kind);
        job.setPayloadJson(json);
        job.setStatus(QUEUED);
        job.setAttempts(0);
        job.setRunAfter(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return repo.save(job).getId();
    }

    /** Requeues running jobs whose lease expired, failing those out of attempts; returns rows touched. */
    public int requeueExpired() {
        int[] n = tx.execute(s -> {
            Instant now = now();
            return new int[] {repo.requeueExpired(now, MAX_ATTEMPTS), repo.failExpired(now, MAX_ATTEMPTS, "lock expired")};
        });
        if (n == null) return 0;
        if (n[1] > 0) log.error("PredictorJobService: {} predictor job(s) failed: lock expired at max attempts", n[1]);
        return n[0] + n[1];
    }

    /** The lease end when this caller took the job, empty when another runner has it. */
    public Optional<Instant> claim(UUID id) {
        return Optional.ofNullable(tx.execute(s -> {
            Instant now = now();
            Instant lockedUntil = now.plus(LOCK);
            return repo.claim(id, now, lockedUntil) == 1 ? lockedUntil : null;
        }));
    }

    public boolean markDone(UUID id, Instant lease) {
        return finish(id, lease, DONE, null, "");
    }

    /** {@code attempts} is the count after this run's claim. */
    public boolean markRetryOrFailed(UUID id, Instant lease, int attempts, String error) {
        if (attempts < MAX_ATTEMPTS) return finish(id, lease, QUEUED, backoff(attempts), error);
        return finish(id, lease, FAILED, null, error);
    }

    /** Hands back a job this build cannot run, so a newer instance can; fails it once out of attempts. */
    // A release consumes an attempt; accepted because rolling deploys are short.
    public boolean markReleasedOrFailed(UUID id, Instant lease, int attempts, String error) {
        if (attempts < MAX_ATTEMPTS) return finish(id, lease, QUEUED, RELEASE_DELAY, error);
        return finish(id, lease, FAILED, null, error);
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public static Duration backoff(int attempts) {
        return Duration.ofMinutes(1L << attempts);
    }

    public static String truncate(String s) {
        return s == null || s.length() <= MAX_ERROR ? s : s.substring(0, MAX_ERROR);
    }

    // A terminal row keeps runAfter = now; only a retry needs a future one.
    private boolean finish(UUID id, Instant lease, String status, Duration delay, String error) {
        Integer n = tx.execute(s -> {
            Instant now = now();
            Instant runAfter = delay == null ? now : now.plus(delay);
            return repo.finish(id, lease, status, runAfter, truncate(error), now);
        });
        return n != null && n == 1;
    }
}
