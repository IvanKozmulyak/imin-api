package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.repository.SurveyResponseRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class SurveyRetentionJobTest {

    // The real now, so rows other tests wrote moments ago are never past the window.
    private static final Instant NOW = Instant.now();

    @Autowired SurveyResponseRepository responses;
    @Autowired AudiencePlanLogic logic;
    @Autowired JdbcTemplate jdbc;

    private final UUID orgId = UUID.randomUUID();
    private Instant cutoff;

    @BeforeEach
    void setUp() {
        cutoff = NOW.truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(logic.logic().legal().retentionDays()));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from survey_responses where org_id = ?", orgId);
    }

    @Test
    void windowIsThreeYears() {
        assertThat(logic.logic().legal().retentionDays()).isEqualTo(1095);
    }

    @Test
    void schedule_isNightlyParisWithItsOwnLock() throws Exception {
        Scheduled scheduled = SurveyRetentionJob.class.getMethod("scheduled").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 30 4 * * *");
        assertThat(scheduled.zone()).isEqualTo("Europe/Paris");
        assertThat(SurveyRetentionJob.class.getMethod("run").getAnnotation(SchedulerLock.class).name())
                .isEqualTo("survey_retention");
    }

    @Test
    void dryRun_countsExpiredAnswersAndDeletesNothing() {
        UUID old = seed(cutoff.minus(Duration.ofDays(1)));
        UUID recent = seed(cutoff.plus(Duration.ofDays(1)));

        assertThat(job(false).run()).isEqualTo(new SurveyRetentionJob.Result(false, 1, 0));

        assertThat(ids()).containsExactlyInAnyOrder(old, recent);
    }

    @Test
    void enabled_deletesAnswersOlderThanTheWindowAndKeepsTheRest() {
        seed(cutoff.minus(Duration.ofDays(1)));
        seed(cutoff.minus(Duration.ofDays(400)));
        UUID atCutoff = seed(cutoff);
        UUID recent = seed(cutoff.plus(Duration.ofDays(1)));

        assertThat(job(true).run()).isEqualTo(new SurveyRetentionJob.Result(true, 2, 2));

        assertThat(ids()).containsExactlyInAnyOrder(atCutoff, recent);
    }

    @Test
    void enabled_nothingExpired_skipsTheDelete() {
        SurveyResponseRepository repo = mock(SurveyResponseRepository.class);
        when(repo.countByCreatedAtBefore(any())).thenReturn(0L);

        assertThat(job(repo, true).run()).isEqualTo(new SurveyRetentionJob.Result(true, 0, 0));

        verify(repo, never()).deleteCreatedBefore(any());
    }

    @Test
    void scheduled_swallowsAFailedRun() {
        SurveyRetentionJob failing = mock(SurveyRetentionJob.class);
        when(failing.run()).thenThrow(new IllegalStateException("db down"));
        @SuppressWarnings("unchecked")
        ObjectProvider<SurveyRetentionJob> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(failing);
        SurveyRetentionJob job = new SurveyRetentionJob(responses, logic, props(true), Clock.fixed(NOW, ZoneOffset.UTC),
                self);

        assertThatCode(job::scheduled).doesNotThrowAnyException();
        verify(failing).run();
    }

    private SurveyRetentionJob job(boolean enabled) {
        return job(responses, enabled);
    }

    @SuppressWarnings("unchecked")
    private SurveyRetentionJob job(SurveyResponseRepository repo, boolean enabled) {
        return new SurveyRetentionJob(repo, logic, props(enabled), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(ObjectProvider.class));
    }

    private static AudiencePlanProperties props(boolean enabled) {
        AudiencePlanProperties p = new AudiencePlanProperties();
        p.setRetentionJobEnabled(enabled);
        return p;
    }

    private UUID seed(Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into survey_responses (id, org_id, event_id, heard_from, notice_version, locale, created_at)"
                + " values (?, ?, ?, 'friend', 'survey-notice-2026-10', 'en', ?)", id, orgId, UUID.randomUUID(),
                Timestamp.from(createdAt));
        return id;
    }

    private List<UUID> ids() {
        return jdbc.queryForList("select id from survey_responses where org_id = ?", UUID.class, orgId);
    }
}
