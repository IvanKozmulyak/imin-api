package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.jobs.PredictorJobService;
import com.imin.iminapi.predictor.model.PredictorJob;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim/requeue queries under real concurrent UPDATEs. Same 2001 clock and empty-window precondition as
 * {@link PredictorJobRunnerTest}, since requeueExpired sweeps every org's jobs.
 */
@IminIntegrationTest
class PredictorJobRunnerPostgresTest {

    private static final Instant T0 = PredictorJobRunnerTest.T0;

    @Autowired PredictorJobRepository repo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    PredictorJobRunnerTest.MutableClock clock;
    PredictorJobService service;
    private final List<UUID> jobIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PredictorJobRunnerTest.assertNoForeignJobBefore(repo, jdbc, PredictorJobRunnerTest.HORIZON);
        clock = new PredictorJobRunnerTest.MutableClock(T0);
        service = new PredictorJobService(repo, clock, tx);
    }

    @AfterEach
    void tearDown() {
        PredictorJobRunnerTest.deleteJobs(jdbc, jobIds);
    }

    @Test
    void concurrentClaimIsExclusive() throws Exception {
        UUID id = service.enqueue("k", null);
        jobIds.add(id);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Optional<Instant>> claim = () -> {
                go.await();
                return service.claim(id);
            };
            Future<Optional<Instant>> a = pool.submit(claim);
            Future<Optional<Instant>> b = pool.submit(claim);
            go.countDown();

            List<Optional<Instant>> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("running");
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void concurrentRequeueFailsAnExpiredLastAttemptForOneCallerOnly() throws Exception {
        clock.advance(PredictorJobService.LOCK.plusMinutes(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Repeated so both callers often read the expired row before either fails it.
            for (int round = 0; round < 20; round++) {
                PredictorJob spent = new PredictorJob();
                spent.setKind("k");
                spent.setStatus("running");
                spent.setAttempts(PredictorJobService.MAX_ATTEMPTS);
                spent.setRunAfter(T0);
                spent.setLockedUntil(T0.plusSeconds(60));
                UUID id = repo.save(spent).getId();
                jobIds.add(id);
                CountDownLatch go = new CountDownLatch(1);
                Callable<List<UUID>> requeue = () -> {
                    go.await();
                    return service.requeueExpired().stream().map(PredictorJob::getId).toList();
                };
                Future<List<UUID>> a = pool.submit(requeue);
                Future<List<UUID>> b = pool.submit(requeue);
                go.countDown();

                List<List<UUID>> got = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

                assertThat(got).as("round " + round).filteredOn(l -> l.contains(id)).hasSize(1);
                assertThat(got).as("round " + round).allSatisfy(l -> assertThat(l).isSubsetOf(id));
                PredictorJob row = repo.findById(id).orElseThrow();
                assertThat(row.getStatus()).isEqualTo("failed");
                assertThat(row.getLastError()).isEqualTo("lock expired");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredLockIsRequeued() {
        UUID id = service.enqueue("k", null);
        jobIds.add(id);
        Instant lease = service.claim(id).orElseThrow();
        PredictorJob spent = new PredictorJob();
        spent.setKind("k");
        spent.setStatus("running");
        spent.setAttempts(3);
        spent.setRunAfter(T0);
        spent.setLockedUntil(T0.plusSeconds(60));
        UUID spentId = repo.save(spent).getId();
        jobIds.add(spentId);

        clock.advance(PredictorJobService.LOCK.plusMinutes(1));
        service.requeueExpired();

        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("queued");
        assertThat(row.getLockedUntil()).isNull();
        assertThat(row.getRunAfter()).isEqualTo(clock.instant());
        assertThat(row.getAttempts()).isEqualTo(1);
        PredictorJob dead = repo.findById(spentId).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo("failed");
        assertThat(dead.getLastError()).isEqualTo("lock expired");
        assertThat(dead.getLockedUntil()).isNull();
        // The stale lease can no longer finish the row.
        assertThat(service.markDone(id, lease)).isFalse();
        assertThat(repo.findById(id).orElseThrow().getStatus()).isEqualTo("queued");
    }
}
