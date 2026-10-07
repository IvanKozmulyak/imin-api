package com.imin.iminapi.predictor;

import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.model.ProjectionBand;
import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.predictor.service.PredictorAlertStore;
import com.imin.iminapi.predictor.service.RadarJob;
import com.imin.iminapi.predictor.service.RadarTimelineService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.predictor.service.ReforecastAlertNotifier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PredictorRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The radar alert end to end: real engine, store, notifier and templates, and the recording mail. Clock pinned at
 * 1 Oct 2026 10:00Z; a French org, so the 15 Oct night is 14 days out (milestone 14). The re-run worsens to risk 8:
 * a seeded pont on the night (4.3, min(4, 3 x 3) = 4) and a 14-day lead under the 21-day minimum (10.1, 2 x 2 = 4).
 */
@IminIntegrationTest
class RadarAlertFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 15);
    private static final Instant START = Instant.parse("2026-10-15T20:00:00Z");
    private static final Instant BEFORE_WINDOW = Instant.parse("2026-09-20T10:00:00Z");

    @Autowired MutableClock clock;
    @Autowired PropertyFlips flips;
    @Autowired DateCheckProperties props;
    @Autowired IminFixtures fx;
    @Autowired RadarJob radarJob;
    @Autowired ReforecastAlertNotifier notifier;
    @Autowired PredictorAlertStore store;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired NotificationRepository notifications;
    @Autowired ReferenceCalendarEntryRepository calendar;
    @Autowired RadarTimelineService timeline;
    @Autowired RecordingEmailService mail;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private final String city = "Paris" + DateCheckControllerTest.letters();
    private final List<UUID> calendarRows = new ArrayList<>();
    private Organization org;
    private User owner;

    @BeforeEach
    void seed() {
        clock.setInstant(NOW);
        flips.set(props, "enabled", true);
        flips.set(props, "allOrgs", true);
        flips.set(props, "radarEnabled", true);
        Organization o = fx.org();
        o.setCountry("FR");
        org = orgs.save(o);
        owner = fx.owner(org);
        cal("pont", NIGHT);
    }

    @AfterEach
    void after() {
        try {
            calendar.deleteAllById(calendarRows);
        } finally {
            PredictorRows.delete(jdbc, List.of(org.getId()));
        }
    }

    /** A country-wide FR row; the whole year then counts as synced for that kind. */
    private void cal(String kind, LocalDate day) {
        ReferenceCalendarEntry e = new ReferenceCalendarEntry();
        e.setCountry("FR");
        e.setRegion("");
        e.setCalendarDate(day);
        e.setKind(kind);
        e.setName("Radar " + kind + " " + city);
        e.setSourceUrl("https://example.org/" + kind);
        e.setSyncedAt(Instant.parse("2026-09-01T00:00:00Z"));
        calendarRows.add(calendar.save(e).getId());
    }

    /** Mail to this test's org contact. */
    private List<RecordingEmailService.SentEmail> ownMail() {
        return mail.sent().stream().filter(m -> org.getContactEmail().equals(m.to())).toList();
    }

    private int ownAlerts() {
        return jdbc.queryForObject("select count(*) from predictor_alert where event_id in "
                + "(select id from events where org_id = ?)", Integer.class, org.getId());
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
                values (?, ?, ?, ?, 'FR', 'house & techno', 'done', 'test', '[]', false, ?, ?, ?)""",
                id, org.getId(), owner.getId(), city, eventId, Timestamp.from(BEFORE_WINDOW), Timestamp.from(BEFORE_WINDOW));
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
        assertThat(ownMail()).singleElement().satisfies(m ->
                assertThat(m.subject()).isEqualTo("Radar Night: date check changed (Good → Move)"));
        Map<String, Object> claim = jdbc.queryForMap(
                "select kind, alert_day, date_check_id from predictor_alert where event_id = ?", e.getId());
        assertThat(claim.get("kind")).isEqualTo("radar");
        assertThat(claim.get("alert_day").toString()).isEqualTo(TODAY.toString());
        assertThat(claim.get("date_check_id")).isEqualTo(radar);
    }

    @Test
    void mutedEventRunsButSendsNoRadarAlert() {
        Event e = liveEvent();
        baseline(e.getId(), NIGHT);
        assertThat(events.updateRadarMuted(e.getId(), true)).isOne();

        runJob();

        UUID radar = radarRow(e.getId());
        Map<String, Object> row = jdbc.queryForMap(
                "select radar_prev_verdict, radar_prev_risk, radar_verdict, radar_risk from date_check where id = ?", radar);
        assertThat(row.get("radar_prev_verdict")).isEqualTo("good");
        assertThat(((Number) row.get("radar_prev_risk")).intValue()).isZero();
        assertThat(row.get("radar_verdict")).isEqualTo("move");
        assertThat(((Number) row.get("radar_risk")).intValue()).isEqualTo(8);
        assertThat(ownAlerts()).isZero();
        assertThat(notificationsFor(owner.getId())).isEmpty();
        assertThat(ownMail()).isEmpty();

        // The muted radar run claimed nothing, so a band crossing the same day still alerts.
        bandCrossing(e);
        assertThat(notificationsFor(owner.getId())).singleElement()
                .satisfies(n -> assertThat(n.getKind()).isEqualTo("predictor.trajectory.tracking_60_85"));
    }

    @Test
    void failedInAppWriteRollsTheClaimBack() {
        Event e = liveEvent();
        baseline(e.getId(), NIGHT);
        try (PgFaults.Fault inAppDown = PgFaults.failWrites(jdbc, "notifications", "user_id", owner.getId())) {
            runJob();
        }

        assertThat(radarRow(e.getId())).isNotNull();
        assertThat(ownAlerts()).isZero();
        assertThat(notificationsFor(owner.getId())).isEmpty();
        assertThat(ownMail()).isEmpty();
        AuthPrincipal p = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
        assertThat(timeline.timeline(p, e.getId()).runs()).singleElement()
                .satisfies(r -> assertThat(r.alert()).isEqualTo("none"));

        // The day was not used up: a later alert that day still claims it.
        bandCrossing(e);
        assertThat(notificationsFor(owner.getId())).singleElement()
                .satisfies(n -> assertThat(n.getKind()).isEqualTo("predictor.trajectory.tracking_60_85"));
        assertThat(jdbc.queryForObject("select kind from predictor_alert where event_id = ?", String.class,
                e.getId())).isEqualTo("band");
    }

    @Test
    void staleBaselineNeverAlerts() {
        Event e = liveEvent();
        baseline(e.getId(), LocalDate.of(2026, 10, 8));

        runJob();

        assertThat(radarRow(e.getId())).isNotNull();
        assertThat(notificationsFor(owner.getId())).isEmpty();
        assertThat(ownMail()).isEmpty();
        assertThat(ownAlerts()).isZero();
    }

    @Test
    void alertIsSentAfterTheRunCommits() {
        Event e = liveEvent();
        baseline(e.getId(), NIGHT);

        runJob();

        assertThat(ownMail()).hasSize(1);
        int sent = mail.sent().indexOf(ownMail().get(0));
        assertThat(mail.sentInTransaction(sent)).isFalse();
        assertThat(radarRow(e.getId())).isNotNull();
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
        assertThat(ownMail()).isEmpty();
    }

    @Test
    void noDoubleAlertWithBandCrossingSameDayRadarFirst() {
        Event e = liveEvent();
        baseline(e.getId(), NIGHT);

        runJob();
        bandCrossing(e);

        assertThat(notificationsFor(owner.getId())).singleElement()
                .satisfies(n -> assertThat(n.getKind()).isEqualTo("predictor.radar.worsened"));
        assertThat(ownMail()).hasSize(1);
    }
}
