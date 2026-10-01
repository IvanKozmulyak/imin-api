package com.imin.iminapi.predictor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.dto.AssumptionsPatch;
import com.imin.iminapi.predictor.dto.DateCheckRequest;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckFindingRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import com.imin.iminapi.predictor.service.DateCheckService;
import com.imin.iminapi.predictor.service.DateCheckService.RadarOutcome;
import com.imin.iminapi.predictor.service.RadarJob;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Radar re-runs with the gate open and the clock fixed at 1 Oct 2026 10:00Z. The org and checks are French, so
 * nights resolve in Europe/Paris (UTC+2 until 25 Oct): the base event's night is 15 Oct, 14 days out, milestone 14.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = {"imin.predictor.date-check.enabled=true",
        "imin.predictor.date-check.all-orgs=true",
        "imin.predictor.date-check.radar-enabled=true"})
class RadarRerunTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 15);
    private static final Instant START = Instant.parse("2026-10-15T20:00:00Z");
    /** Before the milestone-14 window opened on 1 Oct. */
    private static final Instant BEFORE_WINDOW = Instant.parse("2026-09-20T10:00:00Z");

    /** This context's own clock; reset to NOW before each test, advanced by hours only where a test needs order. */
    private static final MutableClock CLOCK = new MutableClock();

    @TestBean Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    static final class MutableClock extends Clock {
        private volatile Instant now = NOW;

        void set(Instant i) { now = i; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @Autowired MockMvc mvc;
    @Autowired DateCheckService service;
    @Autowired RadarJob radarJob;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;
    @Autowired DateCheckFindingRepository findings;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired PredictorJobRepository jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    private org.springframework.transaction.support.TransactionTemplate tx;

    private final ObjectMapper om = new ObjectMapper();

    private Organization org;
    private User owner;

    @BeforeEach
    void seed() {
        CLOCK.set(NOW);
        tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        clean();
        Organization o = new Organization();
        o.setName("Radar Org");
        o.setSlug("rr-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("rr@example.test");
        o.setCountry("FR");
        org = orgs.save(o);
        User u = new User();
        u.setOrgId(org.getId());
        u.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        u.setRole(UserRole.OWNER);
        owner = users.save(u);
    }

    @AfterEach
    void after() {
        clean();
    }

    private void clean() {
        findings.deleteAll();
        checkDates.deleteAll();
        ledger.deleteAll();
        jobs.deleteAll();
        events.deleteAll();
        jdbc.update("update date_check set radar_prev_id = null");
        checks.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    private Authentication mine() {
        return new UsernamePasswordAuthenticationToken(principal(), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    private Event event(EventStatus status, Instant startsAt) {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Radar Night");
        e.setSlug("rr-" + UUID.randomUUID());
        e.setCreatedBy(owner.getId());
        e.setStatus(status);
        e.setStartsAt(startsAt);
        return events.save(e);
    }

    private UUID createEventFrom(UUID checkId) throws Exception {
        String body = om.writeValueAsString(Map.of("name", "From Check", "startsAt", START.toString(),
                "dateCheckId", checkId.toString()));
        String json = mvc.perform(post("/api/v1/events").with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    /** A check row with a chosen id, timestamps and one scored date (the base night by default); no findings. */
    private void rawCheck(UUID id, UUID eventId, Instant createdAt, Instant updatedAt) {
        rawCheck(id, eventId, createdAt, updatedAt, NIGHT);
    }

    private void rawCheck(UUID id, UUID eventId, Instant createdAt, Instant updatedAt, LocalDate date) {
        jdbc.update("""
                insert into date_check (id, org_id, created_by, city, country, genre_family, status,
                    question_bank_version, assumptions_json, research, event_id, created_at, updated_at)
                values (?, ?, ?, 'Paris', 'FR', 'house & techno', 'done', 'test', '[]', false, ?, ?, ?)""",
                id, org.getId(), owner.getId(), eventId, Timestamp.from(createdAt), Timestamp.from(updatedAt));
        jdbc.update("""
                insert into date_check_date (id, date_check_id, candidate_date, verdict, risk_score, opp_score,
                    coverage, rank_order)
                values (?, ?, ?, 'good', 2, 6, 0.800, 1)""", UUID.randomUUID(), id, date);
    }

    private void assertCurrent(Event e, UUID expected) throws Exception {
        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(expected.toString()));
    }

    private void move(Event e, Instant startsAt) {
        Event fresh = events.findById(e.getId()).orElseThrow();
        fresh.setStartsAt(startsAt);
        events.save(fresh);
    }

    /** deleted_at is written only by bulk updates (the column is not updatable through the entity). */
    private void softDelete(Event e) {
        jdbc.update("update events set deleted_at = ? where id = ?", Timestamp.from(NOW), e.getId());
    }

    /** An organizer check made through the service for the event, then dated {@code at} explicitly. */
    private UUID check(UUID eventId, Instant at, LocalDate... dates) {
        DateCheckRequest req = new DateCheckRequest("Paris", "FR", "75011", eventId, "house & techno", "techno",
                List.of(dates), 300, 1500L, "club", 23, 5, List.of("DJ One", "DJ Two"),
                List.of(new DateCheckRequest.KnownEventInput("Rival Night", NIGHT, "Rex", 2)),
                List.of(25, 34), List.of("PT"), 14, false);
        UUID id = service.create(principal(), req).id();
        jdbc.update("update date_check set created_at = ?, updated_at = ? where id = ?",
                Timestamp.from(at), Timestamp.from(at), id);
        return id;
    }

    private List<DateCheck> radarRows(UUID eventId) {
        return checks.findAll().stream()
                .filter(c -> DateCheck.ORIGIN_RADAR.equals(c.getOrigin()) && eventId.equals(c.getEventId()))
                .toList();
    }

    private List<Map<String, Object>> organizerAssumptions(String json) throws Exception {
        List<Map<String, Object>> all = om.readValue(json, new TypeReference<>() {});
        return all.stream().filter(a -> "organizer".equalsIgnoreCase(String.valueOf(a.get("source")))).toList();
    }

    // --- not due ---

    @Test
    void draftEventIsNotRerun() {
        Event draft = event(EventStatus.DRAFT, START);
        check(draft.getId(), BEFORE_WINDOW, NIGHT);
        Event deleted = event(EventStatus.LIVE, START);
        check(deleted.getId(), BEFORE_WINDOW, NIGHT);
        softDelete(deleted);
        Event noStart = event(EventStatus.LIVE, null);
        check(noStart.getId(), BEFORE_WINDOW, NIGHT);

        assertThat(service.radarRerun(UUID.randomUUID())).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(service.radarRerun(draft.getId())).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(service.radarRerun(deleted.getId())).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(service.radarRerun(noStart.getId())).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(checks.findAll()).noneMatch(c -> DateCheck.ORIGIN_RADAR.equals(c.getOrigin()));
    }

    @Test
    void noCheckIsSkipped() {
        Event e = event(EventStatus.LIVE, START);

        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.NO_CHECK);
        assertThat(checks.count()).isZero();
    }

    @Test
    void outsideWindowIsSkipped() {
        // 1 Nov 21:00 Paris (after the DST change) is 31 days out; 1 Oct 22:00 Paris is tonight.
        Event far = event(EventStatus.LIVE, Instant.parse("2026-11-01T20:00:00Z"));
        check(far.getId(), BEFORE_WINDOW, LocalDate.of(2026, 11, 1));
        Event tonight = event(EventStatus.LIVE, Instant.parse("2026-10-01T20:00:00Z"));
        check(tonight.getId(), BEFORE_WINDOW, LocalDate.of(2026, 10, 1));

        assertThat(service.radarRerun(far.getId())).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(service.radarRerun(tonight.getId())).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(radarRows(far.getId())).isEmpty();
        assertThat(radarRows(tonight.getId())).isEmpty();
    }

    @Test
    void checkedInsideWindowIsNotRerun() {
        Event e = event(EventStatus.LIVE, START);
        // 1 Oct 08:00Z is 10:00 Paris on the window's opening day.
        check(e.getId(), Instant.parse("2026-10-01T08:00:00Z"), NIGHT);

        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(radarRows(e.getId())).isEmpty();
    }

    // --- due ---

    @Test
    void staleCheckInsideWindowIsRerun() {
        Event e = event(EventStatus.LIVE, START);
        check(e.getId(), Instant.parse("2026-10-01T08:00:00Z"), LocalDate.of(2026, 10, 24));

        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        assertThat(radarRows(e.getId())).singleElement()
                .satisfies(r -> assertThat(r.getRadarNight()).isEqualTo(NIGHT));
    }

    @Test
    void dueEventGetsRadarRunCopiedFromCurrentCheck() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        UUID prevId = check(e.getId(), BEFORE_WINDOW, NIGHT, LocalDate.of(2026, 10, 24));
        DateCheck prev = checks.findById(prevId).orElseThrow();

        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);

        DateCheck r = radarRows(e.getId()).get(0);
        assertThat(radarRows(e.getId())).hasSize(1);
        assertThat(r.getOrigin()).isEqualTo("radar");
        assertThat(r.getRadarMilestone()).isEqualTo((short) 14);
        assertThat(r.getRadarNight()).isEqualTo(NIGHT);
        assertThat(r.getRadarPrevId()).isEqualTo(prevId);
        assertThat(r.getEventId()).isEqualTo(e.getId());
        assertThat(r.getOrgId()).isEqualTo(org.getId());
        assertThat(r.getCreatedBy()).isEqualTo(owner.getId());
        assertThat(r.getCity()).isEqualTo("Paris");
        assertThat(r.getCountry()).isEqualTo("FR");
        assertThat(r.getPostalCode()).isEqualTo("75011");
        assertThat(r.getGenreFamily()).isEqualTo("house & techno");
        assertThat(r.getSubGenre()).isEqualTo("techno");
        assertThat(r.getCapacity()).isEqualTo(300);
        assertThat(r.getPriceMinor()).isEqualTo(1500L);
        assertThat(r.getFormat()).isEqualTo("club");
        assertThat(r.getStartHour()).isEqualTo((short) 23);
        assertThat(r.getEndHour()).isEqualTo((short) 5);
        assertThat(r.getLineupJson()).isEqualTo(prev.getLineupJson()).contains("DJ One", "DJ Two");
        assertThat(r.getKnownEventsJson()).isEqualTo(prev.getKnownEventsJson()).contains("Rival Night");
        assertThat(r.isResearch()).isFalse();
        assertThat(r.getStatus()).isEqualTo("done");
        assertThat(r.getQuestionBankVersion()).isEqualTo(prev.getQuestionBankVersion());
        List<Map<String, Object>> organizer = organizerAssumptions(r.getAssumptionsJson());
        assertThat(organizer).extracting(a -> a.get("field"))
                .containsExactly("AUDIENCE_AGE", "COMMUNITIES", "PRICE_MINOR", "START_HOUR", "BUYING_LEAD_DAYS");
        assertThat(organizer).isEqualTo(organizerAssumptions(prev.getAssumptionsJson()));
        assertThat(r.getCreatedAt()).isEqualTo(NOW);
        assertThat(r.getUpdatedAt()).isEqualTo(NOW);

        List<DateCheckDate> rows = checkDates.findByDateCheckIdOrderByCandidateDateAsc(r.getId());
        assertThat(rows).extracting(DateCheckDate::getCandidateDate).containsExactly(NIGHT);
        assertThat(findings.findByDateCheckDateIdIn(List.of(rows.get(0).getId()))).isNotEmpty();

        List<Map<String, Object>> ledgerRows = jdbc.queryForList(
                "select event_id, surface from prediction_ledger where date_check_id = ?", r.getId());
        assertThat(ledgerRows).singleElement().satisfies(l -> {
            assertThat(l.get("event_id")).isNull();
            assertThat(l.get("surface")).isEqualTo("DATE_CHECK");
        });
    }

    @Test
    void secondPassWritesNothing() {
        Event e = event(EventStatus.LIVE, START);
        check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);

        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(radarRows(e.getId())).hasSize(1);
    }

    @Test
    void movedEventRerunsSameMilestone() {
        Event e = event(EventStatus.LIVE, START);
        check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        e = events.findById(e.getId()).orElseThrow();
        e.setStartsAt(Instant.parse("2026-10-14T20:00:00Z"));
        events.save(e);

        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        assertThat(radarRows(e.getId())).extracting(DateCheck::getRadarNight, DateCheck::getRadarMilestone)
                .containsExactlyInAnyOrder(tuple(NIGHT, (short) 14),
                        tuple(LocalDate.of(2026, 10, 14), (short) 14));
    }

    @Test
    void movedAwayAndBackReturnsToTheRunThatScoresTheNight() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        UUID first = radarRows(e.getId()).get(0).getId();
        // Same Paris day, same milestone: only the run order moves on.
        CLOCK.set(NOW.plus(Duration.ofHours(1)));
        move(e, Instant.parse("2026-10-14T20:00:00Z"));
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        CLOCK.set(NOW.plus(Duration.ofHours(2)));
        move(e, START);

        // The first run still scores the night inside this milestone's window, so it is current and done.
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(radarRows(e.getId())).hasSize(2);
        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(first.toString()))
                .andExpect(jsonPath("$.dateCheck.stale").value(false));
    }

    // --- linking is not a re-score ---

    @Test
    void linkedCheckWithAnOldScoreIsRerun() throws Exception {
        UUID checkId = check(null, BEFORE_WINDOW, NIGHT);
        UUID eventId = createEventFrom(checkId);
        jdbc.update("update events set status = 'LIVE' where id = ?", eventId);

        assertThat(checks.findById(checkId).orElseThrow().getUpdatedAt()).isEqualTo(BEFORE_WINDOW);
        assertThat(checks.findById(checkId).orElseThrow().getEventId()).isEqualTo(eventId);
        assertThat(service.radarRerun(eventId)).isEqualTo(RadarOutcome.RAN);
    }

    @Test
    void eventCreatedFromACheckShowsItsOriginalScoreTime() throws Exception {
        UUID checkId = check(null, BEFORE_WINDOW, NIGHT);
        UUID eventId = createEventFrom(checkId);

        String json = mvc.perform(get("/api/v1/events/" + eventId + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(checkId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(om.readTree(json).get("dateCheck").get("checkedAt").asText())).isEqualTo(BEFORE_WINDOW);
    }

    @Test
    void stampedCheckSurvivesALaterSaveInTheSameTransaction() {
        UUID checkId = check(null, BEFORE_WINDOW, NIGHT);
        Event e = event(EventStatus.DRAFT, START);

        tx.executeWithoutResult(s -> {
            DateCheck managed = checks.findById(checkId).orElseThrow();
            service.stampEvent(managed, e.getId());
            managed.setCity("Lyon");
            checks.saveAndFlush(managed);
        });

        Map<String, Object> row = jdbc.queryForMap("select event_id, city from date_check where id = ?", checkId);
        assertThat(row.get("event_id")).isEqualTo(e.getId());
        assertThat(row.get("city")).isEqualTo("Lyon");
    }

    // --- ranking of the current check ---

    @Test
    void checkScoringTheNightStaysCurrentWhenAnOlderOneIsPatched() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        UUID a = check(e.getId(), BEFORE_WINDOW, NIGHT);
        move(e, Instant.parse("2026-10-24T20:00:00Z"));
        UUID b = check(e.getId(), Instant.parse("2026-09-25T10:00:00Z"), LocalDate.of(2026, 10, 24));

        service.patchAssumptions(principal(), a, new AssumptionsPatch(null, null, 2500L, null, null));

        assertThat(checks.findById(a).orElseThrow().getUpdatedAt()).isAfter(checks.findById(b).orElseThrow().getUpdatedAt());
        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(b.toString()))
                .andExpect(jsonPath("$.dateCheck.stale").value(false));
    }

    @Test
    void earlyMorningStartFindsTheCheckForThePreviousNight() throws Exception {
        // 01:00Z is 03:00 in Paris, so the night is 15 Oct, the day before the start's UTC day.
        Event e = event(EventStatus.LIVE, Instant.parse("2026-10-16T01:00:00Z"));
        UUID older = UUID.randomUUID();
        rawCheck(older, e.getId(), Instant.parse("2026-09-20T10:00:00Z"), Instant.parse("2026-09-20T10:00:00Z"), NIGHT);
        UUID newer = UUID.randomUUID();
        rawCheck(newer, e.getId(), Instant.parse("2026-09-28T10:00:00Z"), Instant.parse("2026-09-28T10:00:00Z"),
                LocalDate.of(2026, 10, 24));

        assertCurrent(e, older);
    }

    @Test
    void equalUpdatedAtFallsBackToCreatedAt() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        Instant scored = Instant.parse("2026-09-28T10:00:00Z");
        // The winner is inserted first and has the smaller id, so neither order nor id can pick it.
        UUID winner = UUID.fromString("10000000-0000-4000-8000-000000000000");
        UUID loser = UUID.fromString("f0000000-0000-4000-8000-000000000000");
        rawCheck(winner, e.getId(), Instant.parse("2026-09-27T10:00:00Z"), scored);
        rawCheck(loser, e.getId(), Instant.parse("2026-09-26T10:00:00Z"), scored);

        assertCurrent(e, winner);
    }

    @Test
    void fullTieFallsBackToTheIdAsAString() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        Instant at = Instant.parse("2026-09-28T10:00:00Z");
        // Unreachable in practice (timestamps are microseconds); UUID.compareTo is signed and would pick the other.
        UUID winner = UUID.fromString("f0000000-0000-4000-8000-000000000000");
        UUID loser = UUID.fromString("10000000-0000-4000-8000-000000000000");
        assertThat(winner.compareTo(loser)).isNegative();
        rawCheck(winner, e.getId(), at, at);
        rawCheck(loser, e.getId(), at, at);

        assertCurrent(e, winner);
    }

    // --- organizer re-score after a radar run ---

    @Test
    void organizerPatchAfterRadarRunWinsAgain() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        UUID prevId = check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        DateCheck radar = radarRows(e.getId()).get(0);

        service.patchAssumptions(principal(), prevId, new AssumptionsPatch(null, null, 2500L, 22, 21));

        // The patch is stamped by the wall clock, which is past the fixed NOW the radar run used.
        assertThat(checks.findById(prevId).orElseThrow().getUpdatedAt()).isAfter(radar.getUpdatedAt());
        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(prevId.toString()))
                .andExpect(jsonPath("$.dateCheck.stale").value(false));
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(radarRows(e.getId())).hasSize(1);
    }

    @Test
    void nextRadarRunCopiesThePatchedInputs() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        UUID prevId = check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        UUID first = radarRows(e.getId()).get(0).getId();
        service.patchAssumptions(principal(), prevId, new AssumptionsPatch(null, null, 2500L, 22, 21));
        // The patch is stamped by the wall clock, which is past the fixed NOW the radar run used.
        assertThat(checks.findById(prevId).orElseThrow().getUpdatedAt())
                .isAfter(checks.findById(first).orElseThrow().getUpdatedAt());
        // 8 Oct is 7 days out: the next milestone, and a night the patched check did not score.
        move(e, Instant.parse("2026-10-08T20:00:00Z"));

        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);

        DateCheck next = radarRows(e.getId()).stream().filter(r -> !r.getId().equals(first)).findFirst().orElseThrow();
        assertThat(next.getRadarPrevId()).isEqualTo(prevId);
        assertThat(next.getRadarMilestone()).isEqualTo((short) 7);
        assertThat(next.getPriceMinor()).isEqualTo(2500L);
        assertThat(next.getStartHour()).isEqualTo((short) 22);
        assertThat(organizerAssumptions(next.getAssumptionsJson()))
                .isEqualTo(organizerAssumptions(checks.findById(prevId).orElseThrow().getAssumptionsJson()))
                .anySatisfy(a -> {
                    assertThat(a.get("field")).isEqualTo("BUYING_LEAD_DAYS");
                    assertThat(a.get("value")).isEqualTo(21);
                });
    }

    @Test
    void patchedOriginCheckBeatsAnOlderRadarRun() {
        // The origin check names another event, so the event reaches it only through events.date_check_id.
        Event other = event(EventStatus.LIVE, START);
        UUID originId = check(other.getId(), BEFORE_WINDOW, NIGHT);
        Event e = event(EventStatus.LIVE, START);
        jdbc.update("update events set date_check_id = ? where id = ?", originId, e.getId());
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        assertThat(radarRows(e.getId())).singleElement().satisfies(r -> assertThat(r.getRadarPrevId()).isEqualTo(originId));

        service.patchAssumptions(principal(), originId, new AssumptionsPatch(null, null, 2500L, null, null));
        move(e, Instant.parse("2026-10-08T20:00:00Z"));

        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        assertThat(radarRows(e.getId())).filteredOn(r -> r.getRadarMilestone() == 7).singleElement()
                .satisfies(r -> assertThat(r.getRadarPrevId()).isEqualTo(originId));
    }

    // --- candidate query ---

    @Test
    void candidateQueryKeepsOnlyLiveEventsInTheNext32Days() {
        Event past = event(EventStatus.LIVE, NOW.minus(Duration.ofHours(1)));
        Event draft = event(EventStatus.DRAFT, NOW.plus(Duration.ofDays(1)));
        Event deleted = event(EventStatus.LIVE, NOW.plus(Duration.ofDays(2)));
        softDelete(deleted);
        Event tooFar = event(EventStatus.LIVE, NOW.plus(Duration.ofDays(33)));
        Event live = event(EventStatus.LIVE, NOW.plus(Duration.ofDays(20)));
        List<UUID> mine = List.of(past.getId(), draft.getId(), deleted.getId(), tooFar.getId(), live.getId());

        List<UUID> ids = events.findRadarCandidateIds(NOW, NOW.plus(Duration.ofDays(32)));

        assertThat(ids.stream().filter(mine::contains).toList()).containsExactly(live.getId());
    }

    // --- readers ---

    @Test
    void listOmitsRadarRuns() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        UUID prevId = check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        UUID radarId = radarRows(e.getId()).get(0).getId();

        mvc.perform(get("/api/v1/predictions/date-checks").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(prevId.toString()));
        assertThat(checks.existsById(radarId)).isTrue();
    }

    @Test
    void predictionStatusShowsRadarRun() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        DateCheck r = radarRows(e.getId()).get(0);

        String json = mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(r.getId().toString()))
                .andExpect(jsonPath("$.dateCheck.stale").value(false))
                .andExpect(jsonPath("$.dateCheck.forDate").value(NIGHT.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(om.readTree(json).get("dateCheck").get("checkedAt").asText())).isEqualTo(r.getUpdatedAt());
    }

    // --- other writers ---

    @Test
    void assumptionPatchKeepsRadarColumns() {
        Event e = event(EventStatus.LIVE, START);
        UUID prevId = check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId())).isEqualTo(RadarOutcome.RAN);
        UUID radarId = radarRows(e.getId()).get(0).getId();

        service.patchAssumptions(principal(), radarId, new AssumptionsPatch(null, null, null, null, 21));
        // A full-entity save of a loaded row with the radar fields changed in memory writes none of them.
        DateCheck loaded = checks.findById(radarId).orElseThrow();
        loaded.setOrigin(DateCheck.ORIGIN_ORGANIZER);
        loaded.setRadarMilestone((short) 7);
        loaded.setRadarNight(NIGHT.plusDays(1));
        loaded.setRadarPrevId(null);
        checks.saveAndFlush(loaded);

        Map<String, Object> row = jdbc.queryForMap(
                "select origin, radar_milestone, radar_night, radar_prev_id from date_check where id = ?", radarId);
        assertThat(row.get("origin")).isEqualTo("radar");
        assertThat(((Number) row.get("radar_milestone")).intValue()).isEqualTo(14);
        assertThat(row.get("radar_night").toString()).isEqualTo(NIGHT.toString());
        assertThat(row.get("radar_prev_id")).isEqualTo(prevId);
    }

    // --- scheduled bean ---

    @Test
    void scheduledBeanRunWritesRadarRow() {
        Event e = event(EventStatus.LIVE, START);
        check(e.getId(), BEFORE_WINDOW, NIGHT);
        jdbc.update("update shedlock set lock_until = locked_at where name = 'predictor_radar_daily'");

        radarJob.run();

        assertThat(radarRows(e.getId())).singleElement()
                .satisfies(r -> assertThat(r.getRadarMilestone()).isEqualTo((short) 14));
    }
}
