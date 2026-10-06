package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.research.DateCheckResearchSweeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The sweep against a research completing at the same moment, on real Postgres 17 row locks. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestRateLimitConfig.class)
class DateCheckResearchSweepPostgresTest {

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

    private static final UUID ORG = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000f1");

    @Autowired DateCheckRepository checks;
    @Autowired DateCheckResearchSweeper sweeper;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        checks.deleteAll();
        jdbc.update("delete from organizations where id = ?", ORG);
    }

    @Test
    void concurrentCompletionWinsOverTheSweep() throws Exception {
        jdbc.update("insert into organizations (id, name, slug, contact_email, country) "
                + "values (?, 'Sweep Org', 'sweep-org', 's@example.test', 'FR')", ORG);
        DateCheck c = new DateCheck();
        c.setOrgId(ORG);
        c.setCreatedBy(UUID.randomUUID());
        c.setCity("Paris");
        c.setCountry("FR");
        c.setGenreFamily("house & techno");
        c.setStatus("running");
        c.setQuestionBankVersion("qb5-gp1");
        c.setResearch(true);
        c.setResearchStatus(DateCheck.RESEARCH_RUNNING);
        c.setResearchQueuedAt(Instant.now().minus(Duration.ofHours(2)));
        UUID id = checks.save(c).getId();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Completion holds the row lock, as completeResearch does, until the sweep's UPDATE waits on it.
            Future<?> complete = pool.submit(() -> tx.executeWithoutResult(s -> {
                DateCheck row = checks.findLockedById(id).orElseThrow();
                locked.countDown();
                row.setResearchStatus(DateCheck.RESEARCH_DONE);
                row.setStatus("done");
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                checks.saveAndFlush(row);
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Integer> sweep = pool.submit(sweeper::sweep);

            awaitBlockedUpdate();
            release.countDown();

            complete.get(30, TimeUnit.SECONDS);
            assertThat(sweep.get(30, TimeUnit.SECONDS)).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        DateCheck after = checks.findById(id).orElseThrow();
        assertThat(after.getResearchStatus()).isEqualTo("done");
        assertThat(after.getStatus()).isEqualTo("done");
    }

    private void awaitBlockedUpdate() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where wait_event_type = 'Lock' and query ilike '%update date_check%'", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("the sweep never waited on the row lock");
    }
}
