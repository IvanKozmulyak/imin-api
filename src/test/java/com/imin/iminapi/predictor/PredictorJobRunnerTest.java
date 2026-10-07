package com.imin.iminapi.predictor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.jobs.PredictorJobHandler;
import com.imin.iminapi.predictor.jobs.PredictorJobRunner;
import com.imin.iminapi.predictor.jobs.PredictorJobService;
import com.imin.iminapi.predictor.model.PredictorJob;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import com.imin.iminapi.predictor.service.PredictorJson;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The queue on the shared database. The hand-built runner claims and expires every org's jobs, so the clock
 * starts in 2001, before any real job is due or expires, and each test first proves that window is empty.
 */
@IminIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PredictorJobRunnerTest {

    static final Instant T0 = Instant.parse("2001-01-01T10:00:00Z");
    /** Past the furthest any test moves its clock. */
    static final Instant HORIZON = T0.plus(Duration.ofHours(1));

    @Autowired PredictorJobRepository repo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    MutableClock clock;
    PredictorJobService service;
    PredictorProperties props;
    private final List<UUID> jobIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        assertNoForeignJobBefore(repo, jdbc, HORIZON);
        clock = new MutableClock(T0);
        service = new PredictorJobService(repo, clock, tx);
        props = new PredictorProperties();
        props.setJobsPollEnabled(true);
    }

    @AfterEach
    void tearDown() {
        deleteJobs(jdbc, jobIds);
    }

    /** Fails loudly instead of letting a tick claim, retry or expire another test's job. */
    static void assertNoForeignJobBefore(PredictorJobRepository repo, JdbcTemplate jdbc, Instant horizon) {
        assertThat(repo.findClaimable(horizon, PageRequest.of(0, 100))).as("queued jobs due before " + horizon).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from predictor_job where status = 'running' and locked_until < ?",
                Long.class, java.sql.Timestamp.from(horizon))).as("leases expiring before " + horizon).isZero();
    }

    static void deleteJobs(JdbcTemplate jdbc, List<UUID> ids) {
        for (UUID id : ids) jdbc.update("delete from predictor_job where id = ?", id);
    }

    private UUID enqueue(String kind, Object payload) {
        UUID id = service.enqueue(kind, payload);
        jobIds.add(id);
        return id;
    }

    @Test
    void claimTwiceSecondIsEmpty() {
        UUID id = enqueue("k", null);

        Optional<Instant> first = service.claim(id);
        Optional<Instant> second = service.claim(id);

        assertThat(first).contains(T0.plus(PredictorJobService.LOCK));
        assertThat(second).isEmpty();
        assertThat(repo.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void expiredLastAttemptCallsTheTerminalHookOnce() {
        UUID spent = saveRunning(3, T0.plusSeconds(60));
        UUID retried = saveRunning(1, T0.plusSeconds(60));
        List<UUID> hooked = new ArrayList<>();
        PredictorJobHandler h = new PredictorJobHandler() {
            @Override public String kind() { return "k"; }
            @Override public void run(PredictorJob job) {}
            @Override public void onTerminalFailure(PredictorJob job) {
                hooked.add(job.getId());
                throw new IllegalStateException("hook broke for a@b.example");
            }
        };
        PredictorJobRunner r = runner(h);
        clock.advance(PredictorJobService.LOCK.plusMinutes(1));

        List<ILoggingEvent> logged = captureWhile(r::tick);
        r.tick();

        assertThat(hooked).containsExactly(spent);
        assertThat(repo.findById(spent).orElseThrow().getStatus()).isEqualTo("failed");
        assertThat(repo.findById(retried).orElseThrow().getStatus()).isEqualTo("done");
        assertThat(logged).filteredOn(e -> e.getFormattedMessage().contains("hook broke"))
                .singleElement().satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(e.getFormattedMessage()).contains(spent.toString()).doesNotContain("a@b.example");
                });
    }

    @Test
    void thirdFailureMarksFailed() {
        UUID id = enqueue("k", null);
        AtomicInteger runs = new AtomicInteger();
        PredictorJobRunner r = runner(handler("k", j -> {
            runs.incrementAndGet();
            throw new IllegalStateException("x".repeat(5000));
        }));

        r.tick();
        assertThat(repo.findById(id).orElseThrow().getStatus()).isEqualTo("queued");
        clock.advance(Duration.ofMinutes(2));
        r.tick();
        assertThat(repo.findById(id).orElseThrow().getStatus()).isEqualTo("queued");
        clock.advance(Duration.ofMinutes(4));
        r.tick();

        assertThat(runs).hasValue(3);
        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getLockedUntil()).isNull();
        assertThat(row.getLastError()).hasSize(PredictorJobService.MAX_ERROR)
                .startsWith("IllegalStateException: xxx");
    }

    @Test
    void successMarksDone() throws Exception {
        UUID id = enqueue("k", Map.of("n", 7, "at", T0));
        List<String> payloads = new ArrayList<>();
        PredictorJobRunner r = runner(handler("k", j -> payloads.add(j.getPayloadJson())));

        r.tick();

        assertThat(payloads).hasSize(1);
        Map<?, ?> read = PredictorJson.MAPPER.readValue(payloads.get(0), Map.class);
        assertThat(read.get("n")).isEqualTo(7);
        assertThat(read.get("at")).isEqualTo("2001-01-01T10:00:00Z");
        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("done");
        assertThat(row.getLastError()).isEmpty();
        assertThat(row.getLockedUntil()).isNull();
    }

    @Test
    void unknownKindIsReleasedThenFailsAfterThreeClaims() {
        UUID id = enqueue("x", null);
        PredictorJobRunner r = runner();

        r.tick();

        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("queued");
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getRunAfter()).isEqualTo(T0.plus(PredictorJobService.RELEASE_DELAY));
        assertThat(row.getLastError()).isEqualTo("unknown kind: x");
        assertThat(row.getLockedUntil()).isNull();

        clock.advance(PredictorJobService.RELEASE_DELAY);
        r.tick();
        assertThat(repo.findById(id).orElseThrow().getStatus()).isEqualTo("queued");
        assertThat(repo.findById(id).orElseThrow().getAttempts()).isEqualTo(2);
        clock.advance(PredictorJobService.RELEASE_DELAY);
        r.tick();

        row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getLastError()).isEqualTo("unknown kind: x");
        assertThat(row.getLockedUntil()).isNull();
    }

    @Test
    void backoffDoublesPerAttempt() {
        assertThat(PredictorJobService.backoff(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(PredictorJobService.backoff(2)).isEqualTo(Duration.ofMinutes(4));

        UUID id = enqueue("k", null);
        AtomicInteger runs = new AtomicInteger();
        PredictorJobRunner r = runner(handler("k", j -> {
            runs.incrementAndGet();
            throw new RuntimeException("boom");
        }));

        r.tick();
        assertThat(repo.findById(id).orElseThrow().getRunAfter()).isEqualTo(T0.plus(Duration.ofMinutes(2)));
        clock.advance(Duration.ofSeconds(119));
        r.tick();
        assertThat(runs).hasValue(1);
        clock.advance(Duration.ofSeconds(1));
        r.tick();
        assertThat(runs).hasValue(2);
        assertThat(repo.findById(id).orElseThrow().getRunAfter())
                .isEqualTo(T0.plus(Duration.ofMinutes(2)).plus(Duration.ofMinutes(4)));
        assertThat(repo.findById(id).orElseThrow().getLastError()).isEqualTo("RuntimeException: boom");
    }

    @Test
    void duplicateKindFailsFast() {
        assertThatThrownBy(() -> runner(handler("k", j -> {}), handler("k", j -> {})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("k");
    }

    @Test
    void leaseLostLeavesTheNewLeaseInPlace() {
        UUID id = enqueue("k", null);
        List<Instant> newLease = new ArrayList<>();
        PredictorJobRunner r = runner(handler("k", j -> {
            clock.advance(PredictorJobService.LOCK.plusMinutes(1));
            service.requeueExpired();
            newLease.add(service.claim(id).orElseThrow());
        }));

        r.tick();

        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("running");
        assertThat(row.getLockedUntil()).isEqualTo(newLease.get(0));
        assertThat(row.getAttempts()).isEqualTo(2);
    }

    @Test
    void pollDisabledDoesNothing() {
        PredictorJobService svc = mock(PredictorJobService.class);
        PredictorJobRepository r = mock(PredictorJobRepository.class);
        PredictorProperties off = new PredictorProperties();
        off.setJobsPollEnabled(false);

        new PredictorJobRunner(svc, r, List.of(), off).poll();

        verifyNoInteractions(svc, r);
    }

    @Test
    void tickFailureIsLoggedNotThrown(CapturedOutput out) {
        PredictorJobService svc = mock(PredictorJobService.class);
        when(svc.requeueExpired()).thenThrow(new RuntimeException("db down for a@b.example"));
        PredictorJobRunner r = new PredictorJobRunner(svc, mock(PredictorJobRepository.class), List.of(), props);

        assertThatNoException().isThrownBy(r::poll);

        assertThat(out.getOut()).contains("PredictorJobRunner").contains("db down").doesNotContain("a@b.example");
    }

    @Test
    void retryBookkeepingFailureKeepsOriginalError(CapturedOutput out) {
        PredictorJobService svc = mock(PredictorJobService.class);
        PredictorJobRepository r = mock(PredictorJobRepository.class);
        UUID id = UUID.randomUUID();
        PredictorJob job = new PredictorJob();
        job.setId(id);
        job.setKind("k");
        job.setAttempts(1);
        when(svc.now()).thenReturn(T0);
        when(r.findClaimable(any(), any())).thenReturn(List.of(id));
        when(svc.claim(id)).thenReturn(Optional.of(T0.plus(PredictorJobService.LOCK)));
        when(r.findById(id)).thenReturn(Optional.of(job));
        when(svc.markRetryOrFailed(eq(id), any(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("rollback broke"));
        PredictorJobRunner runner = new PredictorJobRunner(svc, r,
                List.of(handler("k", j -> { throw new RuntimeException("handler broke"); })), props);

        assertThatNoException().isThrownBy(runner::tick);

        assertThat(out.getOut()).contains("handler broke").contains("rollback broke");
    }

    @Test
    void enqueueNullPayloadStoresEmptyObject() {
        UUID id = enqueue("k", null);

        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getPayloadJson()).isEqualTo("{}");
        assertThat(row.getStatus()).isEqualTo("queued");
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getRunAfter()).isEqualTo(T0);
        assertThat(row.getKind()).isEqualTo("k");
    }

    @Test
    void enqueueRejectsBlankKind() {
        assertThatThrownBy(() -> service.enqueue(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.enqueue("", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.enqueue("  ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from predictor_job where kind is null or btrim(kind) = ''",
                Long.class)).isZero();
    }

    @Test
    void enqueueRejectsUnserializablePayload() {
        String kind = "k-" + UUID.randomUUID();
        assertThatThrownBy(() -> service.enqueue(kind, new Object())).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from predictor_job where kind = ?", Long.class, kind)).isZero();
    }

    @Test
    void truncateCutsToMaxError() {
        assertThat(PredictorJobService.truncate(null)).isNull();
        assertThat(PredictorJobService.truncate("short")).isEqualTo("short");
        assertThat(PredictorJobService.truncate("y".repeat(2500))).hasSize(PredictorJobService.MAX_ERROR);
    }

    /** Runs {@code work} with a listening appender on the runner and service loggers. */
    static List<ILoggingEvent> captureWhile(Runnable work) {
        List<ch.qos.logback.classic.Logger> loggers = List.of(
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PredictorJobRunner.class),
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PredictorJobService.class));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        loggers.forEach(l -> l.addAppender(appender));
        try {
            work.run();
            return List.copyOf(appender.list);
        } finally {
            loggers.forEach(l -> l.detachAppender(appender));
            appender.stop();
        }
    }

    private UUID saveRunning(int attempts, Instant lockedUntil) {
        PredictorJob j = new PredictorJob();
        j.setKind("k");
        j.setStatus("running");
        j.setAttempts(attempts);
        j.setRunAfter(T0);
        j.setLockedUntil(lockedUntil.truncatedTo(ChronoUnit.MICROS));
        UUID id = repo.save(j).getId();
        jobIds.add(id);
        return id;
    }

    private PredictorJobRunner runner(PredictorJobHandler... handlers) {
        return new PredictorJobRunner(service, repo, List.of(handlers), props);
    }

    static PredictorJobHandler handler(String kind, Consumer<PredictorJob> body) {
        return new PredictorJobHandler() {
            @Override public String kind() { return kind; }
            @Override public void run(PredictorJob job) { body.accept(job); }
        };
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
