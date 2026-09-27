package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The plan list and refresh on Postgres 17: the list queries and the first-plan lock must hold where H2 is lenient. */
@Testcontainers(disabledWithoutDocker = true)
class AudiencePlanListPostgresTest extends AudiencePlanListScenarios {

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

    @Test
    void firstPlanLock_doesNotBlockAnFkInsertOnTheEvent() throws Exception {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                planService.lockFirstPlan(e.getId());
                locked.countDown();
                try {
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            // A checkout-style row referencing the event takes FOR KEY SHARE on it; a lock_timeout turns a wait into a failure.
            Future<Integer> inserted = pool.submit(() -> tx.execute(s -> {
                jdbc.execute("SET LOCAL lock_timeout = '3s'");
                return jdbc.update("insert into event_funnel_events (id, event_id, stage, anon_id) values (?, ?, 'PAGE_VIEW', 'plan-lock')",
                        UUID.randomUUID(), e.getId());
            }));
            assertThat(inserted.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(holder.isDone()).as("the plan lock is still held during the insert").isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
