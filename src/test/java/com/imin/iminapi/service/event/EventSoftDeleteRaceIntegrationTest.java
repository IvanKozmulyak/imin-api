package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgLocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A soft delete racing an entity save, on Postgres 17: READ COMMITTED re-checks the lock's WHERE after the wait. */
@IminIntegrationTest
class EventSoftDeleteRaceIntegrationTest {

    @Autowired EventService eventService;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private final List<UUID> orgIds = new ArrayList<>();
    private UUID orgA;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("Soft delete race");
        o.setSlug("sdr-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("sdr@example.test");
        o.setCountry("DE");
        orgA = orgs.save(o).getId();
        orgIds.add(orgA);
        User u = new User();
        u.setEmail("sdr-" + UUID.randomUUID() + "@example.test");
        u.setFirstName("Ada");
        u.setOrgId(orgA);
        u.setRole(UserRole.MEMBER);
        principal = new AuthPrincipal(users.save(u).getId(), orgA, UserRole.MEMBER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        for (UUID id : orgIds) {
            jdbc.update("DELETE FROM audit_logs WHERE org_id = ?", id);
            jdbc.update("DELETE FROM events WHERE org_id = ?", id);
            jdbc.update("DELETE FROM users WHERE org_id = ?", id);
            jdbc.update("DELETE FROM organizations WHERE id = ?", id);
        }
    }

    @Test
    void entity_loaded_before_a_soft_delete_does_not_clear_deleted_at_on_save() {
        UUID id = draft();
        TransactionTemplate outer = new TransactionTemplate(txManager);
        TransactionTemplate inner = new TransactionTemplate(txManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(s -> {
            Event stale = events.findActive(id).orElseThrow();
            inner.executeWithoutResult(s2 -> assertThat(
                    events.softDeleteNeverPublishedDraft(id, orgA, Instant.now().truncatedTo(ChronoUnit.MICROS)))
                    .isEqualTo(1));
            stale.setName("Renamed");
            events.saveAndFlush(stale);
        });

        assertThat(deletedAt(id)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT name FROM events WHERE id = ?", String.class, id))
                .as("the save itself went through; only deleted_at is out of its reach")
                .isEqualTo("Renamed");
    }

    /** End-to-end pin: no resurrection and a rolled-back 404 (the trailing detail() re-read also ensures it). */
    @Test
    void patch_waiting_on_an_uncommitted_delete_is_404_once_it_commits() throws Exception {
        UUID id = draft();
        assertLosesToConcurrentDelete(id, () -> eventService.patch(principal, id, null,
                new EventPatchRequest("Renamed", null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null)));
        assertThat(jdbc.queryForObject("SELECT name FROM events WHERE id = ?", String.class, id)).isEqualTo("Night");
    }

    /** The incomplete draft would fail validation at once: only the up-front row lock makes it wait and 404. */
    @Test
    void publish_waiting_on_an_uncommitted_delete_is_404_once_it_commits() throws Exception {
        UUID id = draft();
        assertLosesToConcurrentDelete(id, () -> eventService.publish(principal, id));
        assertThat(jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, id))
                .isEqualToIgnoringCase("DRAFT");
    }

    /** The delete holds its row lock uncommitted while the writer starts, then commits. */
    private void assertLosesToConcurrentDelete(UUID id, Callable<?> writer) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> deleter = pool.submit(() -> tx.executeWithoutResult(s -> {
                assertThat(events.softDeleteNeverPublishedDraft(id, orgA, Instant.now().truncatedTo(ChronoUnit.MICROS)))
                        .isEqualTo(1);
                deleted.countDown();
                try {
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> write = pool.submit(writer);
            PgLocks.awaitLockWait(jdbc, "^\\s*update events ", "the writer waits for the delete's row lock");
            assertThat(write.isDone()).as("the writer waits for the delete's row lock").isFalse();

            release.countDown();
            deleter.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> write.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .extracting(Throwable::getCause)
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    });
            assertThat(deletedAt(id)).isNotNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private UUID draft() {
        Event e = new Event();
        e.setOrgId(orgA);
        e.setCreatedBy(principal.userId());
        e.setName("Night");
        e.setSlug("sdr-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        e.setCurrency("EUR");
        return events.save(e).getId();
    }

    private Instant deletedAt(UUID id) {
        java.sql.Timestamp ts = jdbc.queryForObject(
                "SELECT deleted_at FROM events WHERE id = ?", java.sql.Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }
}
