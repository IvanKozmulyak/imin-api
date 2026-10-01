package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailServiceTestConfig;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.model.ProjectionBand;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckFindingRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.rules.Finding;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.RuleEngine;
import com.imin.iminapi.predictor.service.PredictorAlertStore;
import com.imin.iminapi.predictor.service.RadarAlertRule;
import com.imin.iminapi.predictor.service.RadarJob;
import com.imin.iminapi.predictor.service.ReforecastAlertNotifier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The radar alert end to end: real store, notifier and templates, a mocked rule engine and the recording mail.
 * Clock fixed at 1 Oct 2026 10:00Z; a French org, so the 15 Oct night is 14 days out (milestone 14).
 */
@SpringBootTest
@Import({TestRateLimitConfig.class, EmailServiceTestConfig.class})
@TestPropertySource(properties = {"imin.predictor.date-check.enabled=true",
        "imin.predictor.date-check.all-orgs=true",
        "imin.predictor.date-check.radar-enabled=true"})
class RadarAlertFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 15);
    private static final Instant START = Instant.parse("2026-10-15T20:00:00Z");
    private static final Instant BEFORE_WINDOW = Instant.parse("2026-09-20T10:00:00Z");

    @TestBean Clock clock;

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @MockitoBean RuleEngine engine;
    @MockitoSpyBean ReforecastAlertNotifier notifier;

    @Autowired RadarJob radarJob;
    @Autowired PredictorAlertStore store;
    @Autowired QuestionBank bank;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;
    @Autowired DateCheckFindingRepository findings;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired NotificationRepository notifications;
    @Autowired EmailService email;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private RecordingEmailService mail;
    private Organization org;
    private User owner;

    @BeforeEach
    void seed() {
        mail = (RecordingEmailService) email;
        clean();
        Organization o = new Organization();
        o.setName("Radar Alert Org");
        o.setSlug("ra-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("radar-team@example.test");
        o.setCountry("FR");
        org = orgs.save(o);
        User u = new User();
        u.setOrgId(org.getId());
        u.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        u.setRole(UserRole.OWNER);
        owner = users.save(u);
        // 4.1 = 2 x 2 = 4 points, 4.3 = min(4, 3 x 3) = 4: risk 8 >= move_min_risk 7, both star ids checked.
        when(engine.evaluate(any(), any())).thenReturn(List.of(
                Finding.found(q("4.1"), Kind.RISK, 2, Map.of(), null),
                Finding.found(q("4.3"), Kind.RISK, 3, Map.of(), null)));
    }

    @AfterEach
    void after() {
        clean();
    }

    private void clean() {
        jdbc.update("delete from predictor_alert");
        jdbc.update("delete from notifications");
        findings.deleteAll();
        checkDates.deleteAll();
        ledger.deleteAll();
        events.deleteAll();
        jdbc.update("update date_check set radar_prev_id = null");
        checks.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
        if (mail != null) mail.clear();
    }

    private QuestionBank.Question q(String id) {
        return bank.questions().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    private Event liveEvent() {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Radar Night");
        e.setSlug("ra-" + UUID.randomUUID());
        e.setCreatedBy(owner.getId());
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(START);
        return events.save(e);
    }

    /** An organizer check for the event, last scored before the milestone window, with one good row and no findings. */
    private UUID baseline(UUID eventId, LocalDate date) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into date_check (id, org_id, created_by, city, country, genre_family, status,
                    question_bank_version, assumptions_json, research, event_id, created_at, updated_at)
                values (?, ?, ?, 'Paris', 'FR', 'house & techno', 'done', 'test', '[]', false, ?, ?, ?)""",
                id, org.getId(), owner.getId(), eventId, Timestamp.from(BEFORE_WINDOW), Timestamp.from(BEFORE_WINDOW));
        jdbc.update("""
                insert into date_check_date (id, date_check_id, candidate_date, verdict, risk_score, opp_score,
                    coverage, rank_order)
                values (?, ?, ?, 'good', 0, 0, 1.000, 1)""", UUID.randomUUID(), id, date);
        return id;
    }

    private void runJob() {
        jdbc.update("update shedlock set lock_until = locked_at where name = 'predictor_radar_daily'");
        radarJob.run();
    }

    private UUID radarRow(UUID eventId) {
        return jdbc.queryForObject("select id from date_check where origin = 'radar' and event_id = ?", UUID.class,
                eventId);
    }

    private List<Notification> notificationsFor(UUID userId) {
        return notifications.findAll().stream().filter(n -> userId.equals(n.getUserId())).toList();
    }

    private void bandCrossing(Event e) {
        notifier.notifyBandChange(events.findById(e.getId()).orElseThrow(), ProjectionBand.UNDER_60,
                ProjectionBand.TRACKING_60_85, null);
    }

    @Test
    void structuredRiskAlertsImmediately() {
        Event e = liveEvent();
        baseline(e.getId(), NIGHT);

        runJob();

        UUID radar = radarRow(e.getId());
        assertThat(notificationsFor(owner.getId())).singleElement().satisfies(n -> {
            assertThat(n.getKind()).isEqualTo("predictor.radar.worsened");
            assertThat(n.getTitle()).isEqualTo("Radar Night: date check changed (Good → Move)");
            assertThat(n.getBody()).isEqualTo(
                    "Thursday 15 October · Date risk 0/10 → 8/10. Open the Predictor tab to see what we found.");
            assertThat(n.getLink()).isEqualTo("/events/" + e.getId() + "/predictor");
        });
        assertThat(mail.sent()).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo("radar-team@example.test");
            assertThat(m.subject()).isEqualTo("Radar Night: date check changed (Good → Move)");
        });
        Map<String, Object> claim = jdbc.queryForMap(
                "select kind, alert_day, date_check_id from predictor_alert where event_id = ?", e.getId());
        assertThat(claim.get("kind")).isEqualTo("radar");
        assertThat(claim.get("alert_day").toString()).isEqualTo(TODAY.toString());
        assertThat(claim.get("date_check_id")).isEqualTo(radar);
    }

    @Test
    void staleBaselineNeverAlerts() {
        Event e = liveEvent();
        baseline(e.getId(), LocalDate.of(2026, 10, 8));

        runJob();

        assertThat(radarRow(e.getId())).isNotNull();
        assertThat(notificationsFor(owner.getId())).isEmpty();
        assertThat(mail.sent()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from predictor_alert", Integer.class)).isZero();
    }

    @Test
    void alertIsSentAfterTheRunCommits() {
        Event e = liveEvent();
        baseline(e.getId(), NIGHT);
        AtomicBoolean inTx = new AtomicBoolean(true);
        AtomicInteger committedRadarRows = new AtomicInteger(-1);
        doAnswer(inv -> {
            inTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            RadarAlertRule.Alert a = inv.getArgument(0);
            committedRadarRows.set(jdbc.queryForObject(
                    "select count(*) from date_check where origin = 'radar' and id = ?", Integer.class, a.runCheckId()));
            return inv.callRealMethod();
        }).when(notifier).notifyRadarWorsened(any());

        runJob();

        verify(notifier).notifyRadarWorsened(any());
        assertThat(inTx).isFalse();
        assertThat(committedRadarRows).hasValue(1);
    }

    @Test
    void claimFirstWins() {
        UUID eventId = liveEvent().getId();
        assertThat(store.claim(eventId, TODAY, PredictorAlertStore.KIND_BAND, null)).isTrue();
        assertThat(store.claim(eventId, TODAY, PredictorAlertStore.KIND_RADAR, null)).isFalse();
        assertThat(store.claim(eventId, TODAY.plusDays(1), PredictorAlertStore.KIND_RADAR, null)).isTrue();
    }

    @Test
    void claimSurvivesOuterRollback() {
        UUID eventId = liveEvent().getId();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            assertThat(store.claim(eventId, TODAY, PredictorAlertStore.KIND_BAND, null)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from predictor_alert where event_id = ?", Integer.class,
                eventId)).isOne();
    }

    @Test
    void noDoubleAlertWithBandCrossingSameDayBandFirst() {
        Event e = liveEvent();
        baseline(e.getId(), NIGHT);

        bandCrossing(e);
        runJob();

        assertThat(radarRow(e.getId())).isNotNull();
        assertThat(notificationsFor(owner.getId())).singleElement()
                .satisfies(n -> assertThat(n.getKind()).isEqualTo("predictor.trajectory.tracking_60_85"));
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void noDoubleAlertWithBandCrossingSameDayRadarFirst() {
        Event e = liveEvent();
        baseline(e.getId(), NIGHT);

        runJob();
        bandCrossing(e);

        assertThat(notificationsFor(owner.getId())).singleElement()
                .satisfies(n -> assertThat(n.getKind()).isEqualTo("predictor.radar.worsened"));
        assertThat(mail.sent()).hasSize(1);
    }
}
