package com.imin.iminapi.service.analytics;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.service.event.SalesDashboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Retention for the /track funnel log.
 *
 * <p>{@code event_funnel_events} has been append-only since V41 with nothing
 * ever removing a row, and {@code anon_id} is carried onto the order (V62) so
 * those rows are joinable to a named purchaser. These cover the purge itself
 * and the property that makes it safe: every aggregate reader treats an absent
 * stage as zero, so purged history reads as 0 rather than failing.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class FunnelRetentionJobTest {

    @Autowired FunnelEventRepository funnel;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired FunnelRetentionJob job;
    @Autowired AnalyticsProperties properties;
    @Autowired SalesDashboardService salesDashboard;
    @Autowired Clock clock;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private UUID eventId;

    @BeforeEach
    void setUp() {
        wipe();
        Organization org = new Organization();
        org.setName("Retention Org");
        org.setSlug("retention-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hello@retention.example");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        String email = "owner-" + UUID.randomUUID() + "@example.com";
        owner.setEmail(email);
        owner.setEmailLower(email);
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Retention Night");
        e.setSlug("retention-night-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.now().plusSeconds(86_400L));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        eventId = events.save(e).getId();
    }

    @AfterEach
    void tearDown() {
        properties.setFunnelRetentionDays(90);
        // Hand the lock back so a later test class calling this job is not a no-op.
        jdbc.update("update shedlock set lock_until = locked_at");
        wipe();
    }

    @Test
    void purge_deletes_rows_past_the_retention_window_and_keeps_the_rest() {
        insert("old", clock.instant().minus(120, ChronoUnit.DAYS));
        insert("edge", clock.instant().minus(91, ChronoUnit.DAYS));
        insert("recent", clock.instant().minus(10, ChronoUnit.DAYS));

        runJob();

        assertThat(funnel.findAll())
                .extracting(FunnelEvent::getAnonId)
                .containsExactly("recent");
    }

    @Test
    void retention_window_is_configurable() {
        insert("thirty-one", clock.instant().minus(31, ChronoUnit.DAYS));
        insert("ten", clock.instant().minus(10, ChronoUnit.DAYS));
        properties.setFunnelRetentionDays(30);

        runJob();

        assertThat(funnel.findAll())
                .extracting(FunnelEvent::getAnonId)
                .containsExactly("ten");
    }

    /** 0 is an explicit "keep everything", not a silent delete-all. */
    @Test
    void zero_retention_disables_the_purge() {
        insert("ancient", clock.instant().minus(5000, ChronoUnit.DAYS));
        properties.setFunnelRetentionDays(0);

        runJob();

        assertThat(funnel.findAll()).hasSize(1);
    }

    /**
     * The reason a purge is safe: an event whose funnel history has aged out
     * still renders, with zeroes, instead of erroring or reporting nulls.
     */
    @Test
    void aggregate_readers_tolerate_a_fully_purged_event() {
        insert("gone", clock.instant().minus(200, ChronoUnit.DAYS));
        runJob();

        Map<String, Long> byStage = new HashMap<>();
        for (Object[] row : funnel.countDistinctAnonByStage(eventId)) {
            byStage.put((String) row[0], ((Number) row[1]).longValue());
        }
        assertThat(byStage).isEmpty();
        assertThat(byStage.getOrDefault(FunnelEvent.STAGE_PAGE_VIEW, 0L)).isZero();

        assertThatCode(() -> salesDashboard.dashboard(
                new com.imin.iminapi.security.AuthPrincipal(
                        UUID.randomUUID(), orgOf(eventId), UserRole.OWNER, UUID.randomUUID()),
                eventId))
                .doesNotThrowAnyException();
    }

    @Test
    void purge_on_an_empty_table_is_a_no_op() {
        assertThatCode(this::runJob).doesNotThrowAnyException();
    }

    /**
     * {@code run()} carries {@code @SchedulerLock} and ShedLock proxies the bean
     * method itself, so a second direct call inside the same minute is silently
     * skipped ({@code lockAtLeastFor = PT1M}) and the test would assert nothing.
     * Released by expiring the row rather than deleting it: ShedLock's
     * StorageBasedLockProvider remembers the row exists and only ever UPDATEs it
     * afterwards, so a deleted row makes every later acquisition fail instead.
     */
    private void runJob() {
        jdbc.update("update shedlock set lock_until = locked_at");
        job.run();
    }

    private UUID orgOf(UUID id) {
        return events.findById(id).orElseThrow().getOrgId();
    }

    private void insert(String anonId, Instant createdAt) {
        FunnelEvent e = new FunnelEvent();
        e.setEventId(eventId);
        e.setStage(FunnelEvent.STAGE_PAGE_VIEW);
        e.setAnonId(anonId);
        e.setCreatedAt(createdAt);
        funnel.save(e);
    }

    private void wipe() {
        funnel.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }
}
