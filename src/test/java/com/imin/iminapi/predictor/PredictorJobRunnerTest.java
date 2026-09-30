package com.imin.iminapi.predictor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.jobs.PredictorJobHandler;
import com.imin.iminapi.predictor.jobs.PredictorJobRunner;
import com.imin.iminapi.predictor.jobs.PredictorJobService;
import com.imin.iminapi.predictor.model.PredictorJob;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import com.imin.iminapi.predictor.service.PredictorJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
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

@SpringBootTest
@Import(TestRateLimitConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class PredictorJobRunnerTest {

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    @Autowired PredictorJobRepository repo;
    @Autowired TransactionTemplate tx;

    MutableClock clock;
    PredictorJobService service;
    PredictorProperties props;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        clock = new MutableClock(T0);
        service = new PredictorJobService(repo, clock, tx);
        props = new PredictorProperties();
        props.setJobsPollEnabled(true);
    }

    @Test
    void claimIsExclusiveAcrossTwoRunners() {
        UUID id = service.enqueue("k", null);
        AtomicInteger runsA = new AtomicInteger();
        AtomicInteger runsB = new AtomicInteger();
        PredictorJobRunner b = runner(handler("k", j -> runsB.incrementAndGet()));
        PredictorJobRunner a = runner(handler("k", j -> {
            runsA.incrementAndGet();
            b.tick();
        }));

        a.tick();

        assertThat(runsA).hasValue(1);
        assertThat(runsB).hasValue(0);
        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("done");
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void claimTwiceSecondIsEmpty() {
        UUID id = service.enqueue("k", null);

        Optional<Instant> first = service.claim(id);
        Optional<Instant> second = service.claim(id);

        assertThat(first).contains(T0.plus(PredictorJobService.LOCK));
        assertThat(second).isEmpty();
        assertThat(repo.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void expiredLockIsRequeued() {
        UUID id = service.enqueue("k", null);
        Instant lease = service.claim(id).orElseThrow();
        UUID spent = saveRunning(3, T0.plusSeconds(60));

        clock.advance(PredictorJobService.LOCK.plusMinutes(1));
        List<ILoggingEvent> logged = captureWhile(service::requeueExpired);

        assertThat(logged).filteredOn(e -> e.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("1 predictor job(s) failed: lock expired"));
        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("queued");
        assertThat(row.getLockedUntil()).isNull();
        assertThat(row.getRunAfter()).isEqualTo(clock.instant());
        assertThat(row.getAttempts()).isEqualTo(1);
        PredictorJob dead = repo.findById(spent).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo("failed");
        assertThat(dead.getLastError()).isEqualTo("lock expired");
        assertThat(dead.getLockedUntil()).isNull();
        // The stale lease can no longer finish the row.
        assertThat(service.markDone(id, lease)).isFalse();
        assertThat(repo.findById(id).orElseThrow().getStatus()).isEqualTo("queued");
    }

    @Test
    void thirdFailureMarksFailed() {
        UUID id = service.enqueue("k", null);
        AtomicInteger runs = new AtomicInteger();
        PredictorJobRunner r = runner(handler("k", j -> {
            runs.incrementAndGet();
            throw new IllegalStateException("x".repeat(5000));
        }));

        List<ILoggingEvent> first = captureWhile(r::tick);
        assertThat(repo.findById(id).orElseThrow().getStatus()).isEqualTo("queued");
        assertThat(first).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(first).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getThrowableProxy()).isNotNull()
                        .extracting(t -> t.getClassName()).isEqualTo(IllegalStateException.class.getName()));
        clock.advance(Duration.ofMinutes(2));
        r.tick();
        assertThat(repo.findById(id).orElseThrow().getStatus()).isEqualTo("queued");
        clock.advance(Duration.ofMinutes(4));
        List<ILoggingEvent> last = captureWhile(r::tick);

        assertThat(last).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains(id.toString()).contains("failed after 3 attempts")
                        .contains("IllegalStateException: xxx"));
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
        UUID id = service.enqueue("k", Map.of("n", 7, "at", T0));
        List<String> payloads = new ArrayList<>();
        PredictorJobRunner r = runner(handler("k", j -> payloads.add(j.getPayloadJson())));

        r.tick();

        assertThat(payloads).hasSize(1);
        Map<?, ?> read = PredictorJson.MAPPER.readValue(payloads.get(0), Map.class);
        assertThat(read.get("n")).isEqualTo(7);
        assertThat(read.get("at")).isEqualTo("2026-09-30T10:00:00Z");
        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("done");
        assertThat(row.getLastError()).isEmpty();
        assertThat(row.getLockedUntil()).isNull();
    }

    @Test
    void unknownKindIsReleasedThenFailsAfterThreeClaims() {
        UUID id = service.enqueue("x", null);
        PredictorJobRunner r = runner();

        List<ILoggingEvent> first = captureWhile(r::tick);

        assertThat(first).filteredOn(e -> e.getLevel() == Level.ERROR).isEmpty();
        assertThat(first).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains(id.toString()).contains("released"));
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
        List<ILoggingEvent> last = captureWhile(r::tick);

        row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getLastError()).isEqualTo("unknown kind: x");
        assertThat(row.getLockedUntil()).isNull();
        assertThat(last).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains(id.toString()).contains("unknown kind: x"));
    }

    @Test
    void backoffDoublesPerAttempt() {
        assertThat(PredictorJobService.backoff(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(PredictorJobService.backoff(2)).isEqualTo(Duration.ofMinutes(4));

        UUID id = service.enqueue("k", null);
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
    void leaseLostIsWarned(CapturedOutput out) {
        UUID id = service.enqueue("k", null);
        List<Instant> newLease = new ArrayList<>();
        PredictorJobRunner r = runner(handler("k", j -> {
            clock.advance(PredictorJobService.LOCK.plusMinutes(1));
            service.requeueExpired();
            newLease.add(service.claim(id).orElseThrow());
        }));

        r.tick();

        assertThat(out.getOut()).contains("lease lost").contains(id.toString());
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
        UUID id = service.enqueue("k", null);

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
        assertThat(repo.count()).isZero();
    }

    @Test
    void enqueueRejectsUnserializablePayload() {
        assertThatThrownBy(() -> service.enqueue("k", new Object())).isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.count()).isZero();
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
        return repo.save(j).getId();
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
