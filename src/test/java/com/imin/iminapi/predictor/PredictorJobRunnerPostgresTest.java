package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.jobs.PredictorJobService;
import com.imin.iminapi.predictor.model.PredictorJob;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
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

/** The claim/requeue queries on real Postgres 17, whose concurrent UPDATE semantics H2 does not share. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestRateLimitConfig.class)
class PredictorJobRunnerPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.docker.compose.enabled", () -> "false");
    }

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    @Autowired PredictorJobRepository repo;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource dataSource;

    PredictorJobRunnerTest.MutableClock clock;
    PredictorJobService service;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        clock = new PredictorJobRunnerTest.MutableClock(T0);
        service = new PredictorJobService(repo, clock, tx);
    }

    @Test
    void runsOnPostgres() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select version()")) {
            r.next();
            assertThat(r.getString(1)).contains("PostgreSQL 17");
        }
    }

    @Test
    void concurrentClaimIsExclusive() throws Exception {
        UUID id = service.enqueue("k", null);
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
    void expiredLockIsRequeued() {
        UUID id = service.enqueue("k", null);
        Instant lease = service.claim(id).orElseThrow();
        PredictorJob spent = new PredictorJob();
        spent.setKind("k");
        spent.setStatus("running");
        spent.setAttempts(3);
        spent.setRunAfter(T0);
        spent.setLockedUntil(T0.plusSeconds(60));
        UUID spentId = repo.save(spent).getId();

        clock.advance(PredictorJobService.LOCK.plusMinutes(1));
        service.requeueExpired();

        PredictorJob row = repo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("queued");
        assertThat(row.getLockedUntil()).isNull();
        assertThat(row.getRunAfter()).isEqualTo(clock.instant());
        PredictorJob dead = repo.findById(spentId).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo("failed");
        assertThat(dead.getLastError()).isEqualTo("lock expired");
        assertThat(service.markDone(id, lease)).isFalse();
    }
}
