package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckFindingRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.DateCheckService;
import com.imin.iminapi.predictor.service.DateCheckService.RadarOutcome;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Radar timeline and per-event mute with the gate and Radar on. Clock fixed at 1 Oct 2026 10:00Z; a French org,
 * so the 15 Oct night is 14 days out (milestone 14).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = {"imin.predictor.date-check.enabled=true",
        "imin.predictor.date-check.all-orgs=true",
        "imin.predictor.date-check.radar-enabled=true"})
class RadarTimelineTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 15);
    private static final Instant START = Instant.parse("2026-10-15T20:00:00Z");
    private static final Instant BEFORE_WINDOW = Instant.parse("2026-09-20T10:00:00Z");

    @TestBean Clock clock;

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Autowired MockMvc mvc;
    @Autowired DateCheckService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;
    @Autowired DateCheckFindingRepository findings;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper om = new ObjectMapper();

    private Organization org;
    private User owner;

    @BeforeEach
    void seed() {
        clean();
        org = org("Radar Timeline Org");
        owner = user(org);
    }

    @AfterEach
    void after() {
        clean();
    }

    private void clean() {
        jdbc.update("delete from predictor_alert");
        jdbc.update("update events set radar_muted = false");
        findings.deleteAll();
        checkDates.deleteAll();
        ledger.deleteAll();
        events.deleteAll();
        jdbc.update("update date_check set radar_prev_id = null");
        checks.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private Organization org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug("rt-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("rt@example.test");
        o.setCountry("FR");
        return orgs.save(o);
    }

    private User user(Organization o) {
        User u = new User();
        u.setOrgId(o.getId());
        u.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        u.setRole(UserRole.OWNER);
        return users.save(u);
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    private Authentication mine() {
        return new UsernamePasswordAuthenticationToken(principal(), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    private Event event(Organization o, User by) {
        Event e = new Event();
        e.setOrgId(o.getId());
        e.setName("Radar Night");
        e.setSlug("rt-" + UUID.randomUUID());
        e.setCreatedBy(by.getId());
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(START);
        return events.save(e);
    }

    private Event event() {
        return event(org, owner);
    }

    /** A check row written directly: origin, radar key and snapshot columns as given (null = absent). */
    private UUID row(UUID orgId, UUID eventId, Instant createdAt, String origin, Integer milestone,
                     String prevVerdict, Integer prevRisk, String verdict, Integer risk) {
        UUID id = UUID.randomUUID();
        boolean radar = "radar".equals(origin);
        jdbc.update("""
                insert into date_check (id, org_id, created_by, city, country, genre_family, status,
                    question_bank_version, assumptions_json, research, event_id, created_at, updated_at, origin,
                    radar_milestone, radar_night, radar_prev_verdict, radar_prev_risk, radar_verdict, radar_risk)
                values (?, ?, ?, 'Paris', 'FR', 'house & techno', 'done', 'test', '[]', false, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?)""",
                id, orgId, owner.getId(), eventId, Timestamp.from(createdAt), Timestamp.from(createdAt), origin,
                radar ? milestone : null, radar ? NIGHT : null, prevVerdict, prevRisk, verdict, risk);
        return id;
    }

    private UUID radarRun(UUID eventId, Instant createdAt, int milestone, String prevVerdict, Integer prevRisk,
                          String verdict, Integer risk) {
        return row(org.getId(), eventId, createdAt, "radar", milestone, prevVerdict, prevRisk, verdict, risk);
    }

    private void alertClaim(UUID eventId, LocalDate day, String kind, UUID checkId) {
        jdbc.update("insert into predictor_alert (id, event_id, alert_day, kind, date_check_id, created_at)"
                        + " values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), eventId, day, kind, checkId,
                Timestamp.from(NOW));
    }

    private JsonNode getTimeline(UUID eventId) throws Exception {
        String json = mvc.perform(get("/api/v1/events/" + eventId + "/prediction/radar").with(authentication(mine())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    private MockHttpServletRequestBuilder putMute(UUID eventId, String body) {
        MockHttpServletRequestBuilder b = put("/api/v1/events/" + eventId + "/prediction/radar/mute")
                .with(authentication(mine()));
        return body == null ? b : b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private boolean mutedInDb(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select radar_muted from events where id = ?", Boolean.class,
                eventId));
    }

    private static List<String> ids(JsonNode t) {
        List<String> out = new ArrayList<>();
        t.get("runs").forEach(r -> out.add(r.get("dateCheckId").asText()));
        return out;
    }

    // --- event scope ---

    @Test
    void otherOrgEventIs404() throws Exception {
        Organization other = org("Other Org");
        Event theirs = event(other, user(other));

        mvc.perform(get("/api/v1/events/" + theirs.getId() + "/prediction/radar").with(authentication(mine())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("Event not found"));
        mvc.perform(putMute(theirs.getId(), "{\"muted\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("Event not found"));
        assertThat(mutedInDb(theirs.getId())).isFalse();
    }

    @Test
    void deletedEventIs404() throws Exception {
        Event e = event();
        jdbc.update("update events set deleted_at = ? where id = ?", Timestamp.from(NOW), e.getId());

        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction/radar").with(authentication(mine())))
                .andExpect(status().isNotFound());
        mvc.perform(putMute(e.getId(), "{\"muted\":true}")).andExpect(status().isNotFound());
        assertThat(mutedInDb(e.getId())).isFalse();
    }

    // --- runs ---

    @Test
    void runsNewestFirstOnlyThisEventsRadarRows() throws Exception {
        Event e = event();
        Event sibling = event();
        Organization other = org("Other Org");
        Event theirs = event(other, user(other));
        UUID older = radarRun(e.getId(), Instant.parse("2026-09-15T05:50:00Z"), 30, "good", 1, "adjust", 4);
        UUID newer = radarRun(e.getId(), Instant.parse("2026-10-01T03:50:00Z"), 14, "adjust", 4, "move", 7);
        // Each excluded row is newer than both runs, so it would sort first if a filter were missing.
        row(org.getId(), e.getId(), Instant.parse("2026-10-01T06:00:00Z"), "organizer", null, null, null, null, null);
        radarRun(sibling.getId(), Instant.parse("2026-10-01T07:00:00Z"), 14, "good", 0, "good", 0);
        row(other.getId(), e.getId(), Instant.parse("2026-10-01T08:00:00Z"), "radar", 7, "good", 0, "good", 0);
        row(other.getId(), theirs.getId(), Instant.parse("2026-10-01T09:00:00Z"), "radar", 14, "good", 0, "good", 0);

        JsonNode t = getTimeline(e.getId());

        assertThat(t.get("radarOn").asBoolean()).isTrue();
        assertThat(t.get("muted").asBoolean()).isFalse();
        assertThat(ids(t)).containsExactly(newer.toString(), older.toString());
        JsonNode first = t.get("runs").get(0);
        assertThat(first.get("milestone").asInt()).isEqualTo(14);
        assertThat(first.get("night").asText()).isEqualTo(NIGHT.toString());
        assertThat(Instant.parse(first.get("checkedAt").asText())).isEqualTo(Instant.parse("2026-10-01T03:50:00Z"));
        assertThat(first.get("verdictBefore").asText()).isEqualTo("adjust");
        assertThat(first.get("verdictAfter").asText()).isEqualTo("move");
        assertThat(first.get("riskBefore").asInt()).isEqualTo(4);
        assertThat(first.get("riskAfter").asInt()).isEqualTo(7);
        assertThat(first.get("alert").asText()).isEqualTo("none");
        JsonNode second = t.get("runs").get(1);
        assertThat(second.get("milestone").asInt()).isEqualTo(30);
        assertThat(second.get("verdictBefore").asText()).isEqualTo("good");
        assertThat(second.get("verdictAfter").asText()).isEqualTo("adjust");
        assertThat(second.get("riskBefore").asInt()).isEqualTo(1);
        assertThat(second.get("riskAfter").asInt()).isEqualTo(4);
    }

    @Test
    void alertSentOnlyForTheClaimedRun() throws Exception {
        Event e = event();
        UUID a = radarRun(e.getId(), Instant.parse("2026-09-15T05:50:00Z"), 30, "good", 0, "adjust", 4);
        UUID b = radarRun(e.getId(), Instant.parse("2026-10-01T03:50:00Z"), 14, "good", 0, "move", 8);
        alertClaim(e.getId(), LocalDate.of(2026, 10, 1), "radar", b);
        alertClaim(e.getId(), LocalDate.of(2026, 9, 15), "band", null);

        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction/radar").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[*].dateCheckId").value(contains(b.toString(), a.toString())))
                .andExpect(jsonPath("$.runs[*].alert").value(contains("sent", "none")));
    }

    @Test
    void runWithoutBaselineHasNullBefore() throws Exception {
        Event e = event();
        radarRun(e.getId(), Instant.parse("2026-10-01T03:50:00Z"), 14, null, null, "adjust", 5);

        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction/radar").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[0].verdictBefore").value(nullValue()))
                .andExpect(jsonPath("$.runs[0].riskBefore").value(nullValue()))
                .andExpect(jsonPath("$.runs[0].verdictAfter").value("adjust"))
                .andExpect(jsonPath("$.runs[0].riskAfter").value(5));
    }

    @Test
    void timelineKeepsRunTimeVerdicts() throws Exception {
        Event e = event();
        DateCheckRequest req = new DateCheckRequest("Paris", "FR", "75011", e.getId(), "house & techno", "techno",
                List.of(NIGHT), 300, 1500L, "club", 23, 5, List.of("DJ One"), List.of(), List.of(25, 34),
                List.of("PT"), 14, false);
        UUID baseline = service.create(principal(), req).id();
        jdbc.update("update date_check set created_at = ?, updated_at = ? where id = ?",
                Timestamp.from(BEFORE_WINDOW), Timestamp.from(BEFORE_WINDOW), baseline);
        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.RAN);
        JsonNode before = getTimeline(e.getId());
        assertThat(before.get("runs")).hasSize(1);
        UUID run = UUID.fromString(before.get("runs").get(0).get("dateCheckId").asText());
        assertThat(before.get("runs").get(0).get("verdictBefore").isNull()).isFalse();
        assertThat(before.get("runs").get(0).get("verdictAfter").isNull()).isFalse();

        service.patchAssumptions(principal(), baseline, new AssumptionsPatch(null, null, 2500L, 22, 21));
        service.patchAssumptions(principal(), run, new AssumptionsPatch(null, null, 2500L, 22, 21));
        // Whatever the re-scores gave, move both live rows to values neither end held.
        for (UUID id : List.of(baseline, run)) {
            jdbc.update("update date_check_date set verdict = 'not_enough_data', risk_score = 10 where date_check_id = ?",
                    id);
        }

        assertThat(getTimeline(e.getId())).isEqualTo(before);
        assertThat(checks.findById(run).orElseThrow().getOrigin()).isEqualTo(DateCheck.ORIGIN_RADAR);
    }

    // --- mute ---

    @Test
    void muteRoundTrip() throws Exception {
        Event e = event();
        Timestamp updatedAt = jdbc.queryForObject("select updated_at from events where id = ?", Timestamp.class,
                e.getId());

        mvc.perform(putMute(e.getId(), "{\"muted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muted").value(true))
                .andExpect(jsonPath("$.radarOn").value(true));
        assertThat(mutedInDb(e.getId())).isTrue();
        assertThat(getTimeline(e.getId()).get("muted").asBoolean()).isTrue();

        mvc.perform(putMute(e.getId(), "{\"muted\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muted").value(false));
        assertThat(mutedInDb(e.getId())).isFalse();
        assertThat(jdbc.queryForObject("select updated_at from events where id = ?", Timestamp.class, e.getId()))
                .isEqualTo(updatedAt);
    }

    @Test
    void muteWithoutValueIs400() throws Exception {
        Event e = event();

        mvc.perform(putMute(e.getId(), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.muted").value("required"));
        mvc.perform(putMute(e.getId(), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.muted").value("required"));
        assertThat(mutedInDb(e.getId())).isFalse();
    }

    @Test
    void fullEventSaveNeverRevertsMute() throws Exception {
        // A stale entity loaded before the mute and saved after it.
        Event e = event();
        Event stale = events.findById(e.getId()).orElseThrow();
        mvc.perform(putMute(e.getId(), "{\"muted\":true}")).andExpect(status().isOk());
        stale.setName("Renamed");
        events.save(stale);
        assertThat(mutedInDb(e.getId())).isTrue();
        assertThat(jdbc.queryForObject("select name from events where id = ?", String.class, e.getId()))
                .isEqualTo("Renamed");

        // The other order: a save, then the mute.
        Event f = event();
        Event loaded = events.findById(f.getId()).orElseThrow();
        loaded.setName("Saved First");
        events.save(loaded);
        mvc.perform(putMute(f.getId(), "{\"muted\":true}")).andExpect(status().isOk());
        assertThat(mutedInDb(f.getId())).isTrue();

        // A fresh entity with the flag cleared in memory writes nothing to the column.
        Event fresh = events.findById(f.getId()).orElseThrow();
        fresh.setRadarMuted(false);
        fresh.setName("Cleared In Memory");
        events.save(fresh);
        assertThat(mutedInDb(f.getId())).isTrue();
    }

    // --- contract ---

    @Test
    void openApiPublishesRadarTimeline() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/events/{eventId}/prediction/radar'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/events/{eventId}/prediction/radar/mute'].put").exists())
                .andExpect(jsonPath("$.components.schemas.RadarTimelineResponse.properties.radarOn").exists())
                .andExpect(jsonPath("$.components.schemas.RadarTimelineResponse.properties.muted").exists())
                .andExpect(jsonPath("$.components.schemas.RadarTimelineResponse.properties.runs").exists())
                .andExpect(jsonPath("$.components.schemas.RadarRunDto.properties.alert.enum")
                        .value(contains("sent", "none")))
                .andExpect(jsonPath("$.components.schemas.RadarRunDto.properties.verdictAfter.enum")
                        .value(contains("good", "adjust", "move", "not_enough_data")))
                .andExpect(jsonPath("$.components.schemas.RadarMuteRequest.properties.muted").exists());
    }
}
