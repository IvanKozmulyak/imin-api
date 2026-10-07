package com.imin.iminapi.predictor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.dto.AssumptionsPatch;
import com.imin.iminapi.predictor.dto.DateCheckRequest;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckFindingRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.service.DateCheckService;
import com.imin.iminapi.predictor.service.DateCheckService.RadarOutcome;
import com.imin.iminapi.predictor.service.RadarJob;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.PredictorRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Radar re-runs with the gate open and the clock pinned at 1 Oct 2026 10:00Z. The org and checks are French, so
 * nights resolve in Europe/Paris (UTC+2 until 25 Oct): the base event's night is 15 Oct, 14 days out, milestone 14.
 */
@IminIntegrationTest
class RadarRerunTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 15);
    private static final Instant START = Instant.parse("2026-10-15T20:00:00Z");
    /** Before the milestone-14 window opened on 1 Oct. */
    private static final Instant BEFORE_WINDOW = Instant.parse("2026-09-20T10:00:00Z");

    /** Pinned to NOW before each test, advanced by hours only where a test needs order. */
    @Autowired MutableClock clock;
    @Autowired PropertyFlips flips;
    @Autowired DateCheckProperties props;
    @Autowired IminFixtures fx;
    @Autowired MockMvc mvc;
    @Autowired DateCheckService service;
    @Autowired RadarJob radarJob;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;
    @Autowired DateCheckFindingRepository findings;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    private org.springframework.transaction.support.TransactionTemplate tx;

    private final ObjectMapper om = new ObjectMapper();
    private final String city = "Paris" + DateCheckControllerTest.letters();

    private Organization org;
    private User owner;

    @BeforeEach
    void seed() {
        clock.setInstant(NOW);
        flips.set(props, "enabled", true);
        flips.set(props, "allOrgs", true);
        flips.set(props, "radarEnabled", true);
        tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        Organization o = fx.org();
        o.setCountry("FR");
        org = orgs.save(o);
        owner = fx.owner(org);
    }

    @AfterEach
    void after() {
        PredictorRows.delete(jdbc, List.of(org.getId()));
    }

    private List<DateCheck> ownChecks() {
        return checks.findAll().stream().filter(c -> org.getId().equals(c.getOrgId())).toList();
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
        rawCheck(id, eventId, createdAt, updatedAt, date, "good", 2);
    }

    private void rawCheck(UUID id, UUID eventId, Instant createdAt, Instant updatedAt, LocalDate date,
                          String verdict, int risk) {
        jdbc.update("""
                insert into date_check (id, org_id, created_by, city, country, genre_family, status,
                    question_bank_version, assumptions_json, research, event_id, created_at, updated_at)
                values (?, ?, ?, ?, 'FR', 'house & techno', 'done', 'test', '[]', false, ?, ?, ?)""",
                id, org.getId(), owner.getId(), city, eventId, Timestamp.from(createdAt), Timestamp.from(updatedAt));
        jdbc.update("""
                insert into date_check_date (id, date_check_id, candidate_date, verdict, risk_score, opp_score,
                    coverage, rank_order)
                values (?, ?, ?, ?, ?, 6, 0.800, 1)""", UUID.randomUUID(), id, date, verdict, risk);
    }

    private Map<String, Object> snapshotColumns(UUID checkId) {
        return jdbc.queryForMap("select radar_prev_verdict, radar_prev_risk, radar_verdict, radar_risk from date_check"
                + " where id = ?", checkId);
    }

    private static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
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
        DateCheckRequest req = new DateCheckRequest(city, "FR", "75011", eventId, "house & techno", "techno",
                List.of(dates), 300, 1500L, "club", 23, 5, List.of("DJ One", "DJ Two"),
                List.of(new DateCheckRequest.KnownEventInput("Rival Night", NIGHT, "Rex", 2)),
                List.of(25, 34), List.of("PT"), 14, false);
        UUID id = service.create(principal(), req).id();
        jdbc.update("update date_check set created_at = ?, updated_at = ? where id = ?",
                Timestamp.from(at), Timestamp.from(at), id);
        return id;
    }

    private List<DateCheck> radarRows(UUID eventId) {
        return ownChecks().stream()
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

        assertThat(service.radarRerun(UUID.randomUUID()).outcome()).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(service.radarRerun(draft.getId()).outcome()).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(service.radarRerun(deleted.getId()).outcome()).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(service.radarRerun(noStart.getId()).outcome()).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(ownChecks()).noneMatch(c -> DateCheck.ORIGIN_RADAR.equals(c.getOrigin()));
    }

    @Test
    void noCheckIsSkipped() {
        Event e = event(EventStatus.LIVE, START);

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.NO_CHECK);
        assertThat(ownChecks()).isEmpty();
    }

    @Test
    void outsideWindowIsSkipped() {
        // 1 Nov 21:00 Paris (after the DST change) is 31 days out; 1 Oct 22:00 Paris is tonight.
        Event far = event(EventStatus.LIVE, Instant.parse("2026-11-01T20:00:00Z"));
        check(far.getId(), BEFORE_WINDOW, LocalDate.of(2026, 11, 1));
        Event tonight = event(EventStatus.LIVE, Instant.parse("2026-10-01T20:00:00Z"));
        check(tonight.getId(), BEFORE_WINDOW, LocalDate.of(2026, 10, 1));

        assertThat(service.radarRerun(far.getId()).outcome()).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(service.radarRerun(tonight.getId()).outcome()).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(radarRows(far.getId())).isEmpty();
        assertThat(radarRows(tonight.getId())).isEmpty();
    }

    @Test
    void checkedInsideWindowIsNotRerun() {
        Event e = event(EventStatus.LIVE, START);
        // 1 Oct 08:00Z is 10:00 Paris on the window's opening day.
        check(e.getId(), Instant.parse("2026-10-01T08:00:00Z"), NIGHT);

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(radarRows(e.getId())).isEmpty();
    }

    // --- due ---

    @Test
    void staleCheckInsideWindowIsRerun() {
        Event e = event(EventStatus.LIVE, START);
        check(e.getId(), Instant.parse("2026-10-01T08:00:00Z"), LocalDate.of(2026, 10, 24));

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
        assertThat(radarRows(e.getId())).singleElement()
                .satisfies(r -> assertThat(r.getRadarNight()).isEqualTo(NIGHT));
    }

    @Test
    void dueEventGetsRadarRunCopiedFromCurrentCheck() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        UUID prevId = check(e.getId(), BEFORE_WINDOW, NIGHT, LocalDate.of(2026, 10, 24));
        DateCheck prev = checks.findById(prevId).orElseThrow();

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);

        DateCheck r = radarRows(e.getId()).get(0);
        assertThat(radarRows(e.getId())).hasSize(1);
        assertThat(r.getOrigin()).isEqualTo("radar");
        assertThat(r.getRadarMilestone()).isEqualTo((short) 14);
        assertThat(r.getRadarNight()).isEqualTo(NIGHT);
        assertThat(r.getRadarPrevId()).isEqualTo(prevId);
        assertThat(r.getEventId()).isEqualTo(e.getId());
        assertThat(r.getOrgId()).isEqualTo(org.getId());
        assertThat(r.getCreatedBy()).isEqualTo(owner.getId());
        assertThat(r.getCity()).isEqualTo(city);
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
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(radarRows(e.getId())).hasSize(1);
    }

    @Test
    void movedEventRerunsSameMilestone() {
        Event e = event(EventStatus.LIVE, START);
        check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
        e = events.findById(e.getId()).orElseThrow();
        e.setStartsAt(Instant.parse("2026-10-14T20:00:00Z"));
        events.save(e);

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
        assertThat(radarRows(e.getId())).extracting(DateCheck::getRadarNight, DateCheck::getRadarMilestone)
                .containsExactlyInAnyOrder(tuple(NIGHT, (short) 14),
                        tuple(LocalDate.of(2026, 10, 14), (short) 14));
    }

    @Test
    void movedAwayAndBackReturnsToTheRunThatScoresTheNight() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
        UUID first = radarRows(e.getId()).get(0).getId();
        // Same Paris day, same milestone: only the run order moves on.
        clock.setInstant(NOW.plus(Duration.ofHours(1)));
        move(e, Instant.parse("2026-10-14T20:00:00Z"));
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
        clock.setInstant(NOW.plus(Duration.ofHours(2)));
        move(e, START);

        // The first run still scores the night inside this milestone's window, so it is current and done.
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.NOT_DUE);
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
        assertThat(service.radarRerun(eventId).outcome()).isEqualTo(RadarOutcome.RAN);
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

    /**
     * Equal updatedAt: the later createdAt wins although it is inserted first with the smaller id. Full tie: the id as
     * a string wins (unreachable in practice, timestamps are microseconds); UUID.compareTo is signed and would not.
     * The ids are fixed because the order is the point; each test deletes its org's checks.
     */
    @ParameterizedTest
    @CsvSource({
            "10000000-0000-4000-8000-000000000000, 2026-09-27T10:00:00Z, f0000000-0000-4000-8000-000000000000, 2026-09-26T10:00:00Z",
            "f0000000-0000-4000-8000-000000000000, 2026-09-28T10:00:00Z, 10000000-0000-4000-8000-000000000000, 2026-09-28T10:00:00Z"})
    void currentCheckTieBreaks(UUID winner, Instant winnerCreated, UUID loser, Instant loserCreated) throws Exception {
        Event e = event(EventStatus.LIVE, START);
        Instant scored = Instant.parse("2026-09-28T10:00:00Z");
        // The full-tie row only proves the string order if UUID.compareTo would pick the other id.
        if (winnerCreated.equals(loserCreated)) assertThat(winner.compareTo(loser)).isNegative();
        rawCheck(winner, e.getId(), winnerCreated, scored);
        rawCheck(loser, e.getId(), loserCreated, scored);

        assertCurrent(e, winner);
    }

    // --- organizer re-score after a radar run ---

    @Test
    void organizerPatchAfterRadarRunWinsAgain() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        UUID prevId = check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
        DateCheck radar = radarRows(e.getId()).get(0);

        service.patchAssumptions(principal(), prevId, new AssumptionsPatch(null, null, 2500L, 22, 21));

        // The patch is stamped by the wall clock, which is past the fixed NOW the radar run used.
        assertThat(checks.findById(prevId).orElseThrow().getUpdatedAt()).isAfter(radar.getUpdatedAt());
        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(prevId.toString()))
                .andExpect(jsonPath("$.dateCheck.stale").value(false));
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.NOT_DUE);
        assertThat(radarRows(e.getId())).hasSize(1);
    }

    @Test
    void nextRadarRunCopiesThePatchedInputs() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        UUID prevId = check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
        UUID first = radarRows(e.getId()).get(0).getId();
        service.patchAssumptions(principal(), prevId, new AssumptionsPatch(null, null, 2500L, 22, 21));
        // The patch is stamped by the wall clock, which is past the fixed NOW the radar run used.
        assertThat(checks.findById(prevId).orElseThrow().getUpdatedAt())
                .isAfter(checks.findById(first).orElseThrow().getUpdatedAt());
        // 8 Oct is 7 days out: the next milestone, and a night the patched check did not score.
        move(e, Instant.parse("2026-10-08T20:00:00Z"));

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);

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
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
        assertThat(radarRows(e.getId())).singleElement().satisfies(r -> assertThat(r.getRadarPrevId()).isEqualTo(originId));

        service.patchAssumptions(principal(), originId, new AssumptionsPatch(null, null, 2500L, null, null));
        move(e, Instant.parse("2026-10-08T20:00:00Z"));

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
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

        List<UUID> ids = events.findRadarCandidateIds(NOW, NOW.plus(Duration.ofDays(32)));

        assertThat(ids).contains(live.getId())
                .doesNotContain(past.getId(), draft.getId(), deleted.getId(), tooFar.getId());
    }

    // --- readers ---

    @Test
    void listOmitsRadarRuns() throws Exception {
        Event e = event(EventStatus.LIVE, START);
        UUID prevId = check(e.getId(), BEFORE_WINDOW, NIGHT);
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
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
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
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
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
        UUID radarId = radarRows(e.getId()).get(0).getId();
        Map<String, Object> snapshot = snapshotColumns(radarId);
        assertThat(snapshot.values()).doesNotContainNull();

        service.patchAssumptions(principal(), radarId, new AssumptionsPatch(null, null, null, null, 21));
        service.patchAssumptions(principal(), prevId, new AssumptionsPatch(null, null, 2500L, null, 21));
        // A full-entity save of a loaded row with the radar fields changed in memory writes none of them.
        DateCheck loaded = checks.findById(radarId).orElseThrow();
        loaded.setOrigin(DateCheck.ORIGIN_ORGANIZER);
        loaded.setRadarMilestone((short) 7);
        loaded.setRadarNight(NIGHT.plusDays(1));
        loaded.setRadarPrevId(null);
        loaded.setRadarPrevVerdict("good".equals(snapshot.get("radar_prev_verdict")) ? "move" : "good");
        loaded.setRadarPrevRisk((short) (intOrNull(snapshot.get("radar_prev_risk")) == 0 ? 1 : 0));
        loaded.setRadarVerdict("good".equals(snapshot.get("radar_verdict")) ? "move" : "good");
        loaded.setRadarRisk((short) (intOrNull(snapshot.get("radar_risk")) == 0 ? 1 : 0));
        checks.saveAndFlush(loaded);

        assertThat(snapshotColumns(radarId)).isEqualTo(snapshot);

        Map<String, Object> row = jdbc.queryForMap(
                "select origin, radar_milestone, radar_night, radar_prev_id from date_check where id = ?", radarId);
        assertThat(row.get("origin")).isEqualTo("radar");
        assertThat(((Number) row.get("radar_milestone")).intValue()).isEqualTo(14);
        assertThat(row.get("radar_night").toString()).isEqualTo(NIGHT.toString());
        assertThat(row.get("radar_prev_id")).isEqualTo(prevId);
    }

    // --- run-time snapshot ---

    @Test
    void radarRunRecordsBothEnds() {
        Event e = event(EventStatus.LIVE, START);
        UUID base = UUID.randomUUID();
        // A baseline value the engine does not produce for these inputs, so the two ends cannot be confused.
        rawCheck(base, e.getId(), BEFORE_WINDOW, BEFORE_WINDOW, NIGHT, "move", 9);

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);

        UUID radarId = radarRows(e.getId()).get(0).getId();
        DateCheckDate own = checkDates.findByDateCheckIdOrderByCandidateDateAsc(radarId).get(0);
        assertThat(tuple(own.getVerdict(), (int) own.getRiskScore())).isNotEqualTo(tuple("move", 9));
        Map<String, Object> row = snapshotColumns(radarId);
        assertThat(row.get("radar_prev_verdict")).isEqualTo("move");
        assertThat(intOrNull(row.get("radar_prev_risk"))).isEqualTo(9);
        assertThat(row.get("radar_verdict")).isEqualTo(own.getVerdict());
        assertThat(intOrNull(row.get("radar_risk"))).isEqualTo((int) own.getRiskScore());
    }

    @Test
    void staleBaselineRecordsNoBefore() {
        Event e = event(EventStatus.LIVE, START);
        rawCheck(UUID.randomUUID(), e.getId(), BEFORE_WINDOW, BEFORE_WINDOW, LocalDate.of(2026, 10, 24));

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);

        UUID radarId = radarRows(e.getId()).get(0).getId();
        DateCheckDate own = checkDates.findByDateCheckIdOrderByCandidateDateAsc(radarId).get(0);
        Map<String, Object> row = snapshotColumns(radarId);
        assertThat(row.get("radar_prev_verdict")).isNull();
        assertThat(row.get("radar_prev_risk")).isNull();
        assertThat(row.get("radar_verdict")).isEqualTo(own.getVerdict());
        assertThat(intOrNull(row.get("radar_risk"))).isEqualTo((int) own.getRiskScore());
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

    // --- gate closed ---

    /** Date check and radar on, but the org is on no beta list: a due event is skipped and nothing is written. */
    @Test
    void closedGateIsSkipped() {
        flips.set(props, "allOrgs", false);
        flips.set(props, "betaOrgIds", Set.of());
        Event e = event(EventStatus.LIVE, START);
        UUID base = UUID.randomUUID();
        rawCheck(base, e.getId(), BEFORE_WINDOW, BEFORE_WINDOW);

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.GATE_CLOSED);
        assertThat(ownChecks()).extracting(DateCheck::getId).containsExactly(base);
    }
}
