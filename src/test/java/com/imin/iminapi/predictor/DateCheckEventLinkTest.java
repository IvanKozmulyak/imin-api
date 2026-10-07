package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An event linked to a date check, gate open for all orgs, clock pinned at 1 Oct 2026. The orgs are French, so checks
 * score in Europe/Paris (UTC+2 until 25 Oct, UTC+1 after): a 20:00Z start is the same calendar night.
 */
@IminIntegrationTest
class DateCheckEventLinkTest {

    private static final LocalDate OCT24 = LocalDate.of(2026, 10, 24);
    private static final LocalDate NOV14 = LocalDate.of(2026, 11, 14);
    private static final LocalDate DEC5 = LocalDate.of(2026, 12, 5);

    @Autowired MutableClock clock;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired DateCheckProperties props;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;

    private final ObjectMapper om = new ObjectMapper();
    private final String city = "Paris" + DateCheckControllerTest.letters();
    private final List<UUID> createdOrgs = new ArrayList<>();

    private Organization org;
    private User owner;
    private Organization otherOrg;
    private User otherOwner;

    @BeforeEach
    void seed() {
        clock.setInstant(Instant.parse("2026-10-01T10:00:00Z"));
        flips.set(props, "enabled", true);
        flips.set(props, "allOrgs", true);
        org = org();
        owner = fx.owner(org);
        otherOrg = org();
        otherOwner = fx.owner(otherOrg);
    }

    @AfterEach
    void after() {
        PredictorRows.delete(jdbc, createdOrgs);
    }

    private Organization org() {
        Organization o = fx.org();
        createdOrgs.add(o.getId());
        o.setCountry("FR");
        return orgs.save(o);
    }

    private long ownEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM events WHERE org_id = ?", Long.class, org.getId());
    }

    private Authentication mine() {
        AuthPrincipal p = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
        return new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    /** A stored check with the given (date, rank) rows; no findings. */
    private DateCheck seedCheck(Organization o, UUID eventId, Object... dateRank) {
        return seedCheckAt(Instant.parse("2026-09-01T10:00:00Z"), o, eventId, dateRank);
    }

    private DateCheck seedCheckAt(Instant createdAt, Organization o, UUID eventId, Object... dateRank) {
        DateCheck c = new DateCheck();
        c.setOrgId(o.getId());
        c.setCreatedBy(owner.getId());
        c.setCity(city);
        c.setCountry("FR");
        c.setGenreFamily("house & techno");
        c.setStatus("done");
        c.setQuestionBankVersion("test");
        c.setEventId(eventId);
        c.setCreatedAt(createdAt);
        // Never re-scored, so last scored when made: the current check is picked by updatedAt.
        c.setUpdatedAt(createdAt);
        c = checks.save(c);
        for (int i = 0; i < dateRank.length; i += 2) {
            DateCheckDate d = new DateCheckDate();
            d.setDateCheckId(c.getId());
            d.setCandidateDate((LocalDate) dateRank[i]);
            d.setVerdict("good");
            d.setRiskScore((short) 2);
            d.setOppScore((short) 6);
            d.setCoverage(new BigDecimal("0.800"));
            Integer rank = (Integer) dateRank[i + 1];
            d.setRankOrder(rank == null ? null : rank.shortValue());
            checkDates.save(d);
        }
        return c;
    }

    private static Instant night(LocalDate d) {
        return d.atTime(20, 0).toInstant(ZoneOffset.UTC);
    }

    private ResultActions createEvent(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/events").with(authentication(mine()))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private UUID createdEvent(Map<String, Object> body) throws Exception {
        String json = createEvent(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    private Map<String, Object> eventBody(LocalDate night, UUID dateCheckId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", "Linked Night");
        b.put("startsAt", night(night).toString());
        if (dateCheckId != null) b.put("dateCheckId", dateCheckId.toString());
        return b;
    }

    private ResultActions patchEvent(UUID id, Map<String, Object> body) throws Exception {
        return mvc.perform(patch("/api/v1/events/" + id).with(authentication(mine()))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private ResultActions prediction(UUID eventId) throws Exception {
        return mvc.perform(get("/api/v1/events/" + eventId + "/prediction").with(authentication(mine())));
    }

    private JsonNode postCheck(UUID eventId, LocalDate... dates) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("city", city);
        b.put("postalCode", "75011");
        b.put("genreFamily", "house & techno");
        b.put("dates", List.of(dates).stream().map(LocalDate::toString).toList());
        b.put("capacity", 300);
        b.put("knownEvents", List.of());
        if (eventId != null) b.put("eventId", eventId.toString());
        String json = mvc.perform(post("/api/v1/predictions/date-checks").with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(b)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    // --- create with dateCheckId ---

    @Test
    void createWithDateCheckLinks() throws Exception {
        DateCheck c = seedCheck(org, null, OCT24, 1);

        UUID id = createdEvent(eventBody(OCT24, c.getId()));

        assertThat(events.findById(id).orElseThrow().getDateCheckId()).isEqualTo(c.getId());
        assertThat(checks.findById(c.getId()).orElseThrow().getEventId()).isEqualTo(id);
    }

    @Test
    void createKeepsAnExistingCheckEventId() throws Exception {
        UUID a = createdEvent(eventBody(OCT24, null));
        DateCheck c = seedCheck(org, a, OCT24, 1);

        UUID b = createdEvent(eventBody(OCT24, c.getId()));

        assertThat(checks.findById(c.getId()).orElseThrow().getEventId()).isEqualTo(a);
        assertThat(events.findById(b).orElseThrow().getDateCheckId()).isEqualTo(c.getId());
    }

    @Test
    void foreignDateCheckIs404() throws Exception {
        DateCheck theirs = seedCheck(otherOrg, null, OCT24, 1);

        createEvent(eventBody(OCT24, theirs.getId())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(ownEvents()).isZero();
        assertThat(checks.findById(theirs.getId()).orElseThrow().getEventId()).isNull();
    }

    @Test
    void unknownDateCheckIs404() throws Exception {
        createEvent(eventBody(OCT24, UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Date check not found"));

        assertThat(ownEvents()).isZero();
    }

    @Test
    void patchIgnoresDateCheckId() throws Exception {
        UUID id = createdEvent(eventBody(OCT24, null));
        DateCheck c = seedCheck(org, null, OCT24, 1);

        patchEvent(id, Map.of("name", "Renamed", "dateCheckId", c.getId().toString()))
                .andExpect(status().isOk());

        assertThat(events.findById(id).orElseThrow().getDateCheckId()).isNull();
        assertThat(checks.findById(c.getId()).orElseThrow().getEventId()).isNull();
    }

    // --- subGenre ---

    @Test
    void knownSubGenreIsStoredAndReturned() throws Exception {
        UUID id = createdEvent(eventBody(OCT24, null));

        patchEvent(id, Map.of("subGenre", " Melodic TECHNO ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.subGenre").value("melodic techno"));

        assertThat(events.findById(id).orElseThrow().getSubGenre()).isEqualTo("melodic techno");
    }

    static Stream<Arguments> invalidSubGenres() {
        return Stream.of(Arguments.of("polka", "unknown"), Arguments.of("x".repeat(65), null));
    }

    /** An unknown or over-long subGenre is a 400 and stores nothing; a null code only requires the field. */
    @ParameterizedTest
    @MethodSource("invalidSubGenres")
    void invalidSubGenreIs400(String subGenre, String code) throws Exception {
        UUID id = createdEvent(eventBody(OCT24, null));

        ResultActions r = patchEvent(id, Map.of("subGenre", subGenre)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
        if (code == null) r.andExpect(jsonPath("$.error.fields.subGenre").exists());
        else r.andExpect(jsonPath("$.error.fields.subGenre").value(code));

        assertThat(events.findById(id).orElseThrow().getSubGenre()).isNull();
    }

    @Test
    void blankSubGenreClears() throws Exception {
        UUID id = createdEvent(eventBody(OCT24, null));
        patchEvent(id, Map.of("subGenre", "techno")).andExpect(status().isOk());

        patchEvent(id, Map.of("subGenre", "  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.subGenre").doesNotExist());

        assertThat(events.findById(id).orElseThrow().getSubGenre()).isNull();
    }

    // --- prediction.dateCheck ---

    @Test
    void predictionStatusIncludesDateCheck() throws Exception {
        String checkId = postCheck(null, OCT24, DEC5).get("id").asText();
        UUID id = createdEvent(eventBody(OCT24, UUID.fromString(checkId)));

        prediction(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("none"))
                .andExpect(jsonPath("$.dateCheck.id").value(checkId))
                .andExpect(jsonPath("$.dateCheck.forDate").value(OCT24.toString()))
                .andExpect(jsonPath("$.dateCheck.stale").value(false))
                .andExpect(jsonPath("$.dateCheck.result.date").value(OCT24.toString()))
                .andExpect(jsonPath("$.dateCheck.checkedAt").exists());
    }

    @Test
    void editingDateMarksStale() throws Exception {
        DateCheck c = seedCheck(org, null, OCT24, 2, DEC5, 1);
        UUID id = createdEvent(eventBody(OCT24, c.getId()));
        prediction(id).andExpect(jsonPath("$.dateCheck.stale").value(false));

        patchEvent(id, Map.of("startsAt", night(NOV14).toString())).andExpect(status().isOk());

        prediction(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(c.getId().toString()))
                .andExpect(jsonPath("$.dateCheck.stale").value(true))
                .andExpect(jsonPath("$.dateCheck.forDate").value(DEC5.toString()))
                .andExpect(jsonPath("$.dateCheck.result.date").value(DEC5.toString()))
                .andExpect(jsonPath("$.dateCheck.result.rank").value(1));
    }

    @Test
    void checkAgainWithEventIdBecomesCurrent() throws Exception {
        DateCheck c = seedCheck(org, null, OCT24, 2, DEC5, 1);
        UUID id = createdEvent(eventBody(OCT24, c.getId()));
        patchEvent(id, Map.of("startsAt", night(NOV14).toString())).andExpect(status().isOk());

        String again = postCheck(id, NOV14).get("id").asText();

        prediction(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(again))
                .andExpect(jsonPath("$.dateCheck.stale").value(false))
                .andExpect(jsonPath("$.dateCheck.forDate").value(NOV14.toString()));
    }

    @Test
    void checkWithEventIdLinksAnUnlinkedEvent() throws Exception {
        UUID id = createdEvent(eventBody(DEC5, null));

        String checkId = postCheck(id, DEC5).get("id").asText();

        prediction(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(checkId))
                .andExpect(jsonPath("$.dateCheck.stale").value(false));
    }

    @Test
    void newerOriginCheckWinsOverOlderCheckNamingTheEvent() throws Exception {
        UUID other = createdEvent(eventBody(OCT24, null));
        // The origin already names another event, so creating from it leaves its event_id alone.
        DateCheck origin = seedCheckAt(Instant.parse("2026-09-20T10:00:00Z"), org, other, OCT24, 1);
        UUID id = createdEvent(eventBody(OCT24, origin.getId()));
        seedCheckAt(Instant.parse("2026-09-01T10:00:00Z"), org, id, OCT24, 1);

        prediction(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.id").value(origin.getId().toString()));
    }

    @Test
    void foreignCheckOnEventIsIgnored() throws Exception {
        UUID id = createdEvent(eventBody(OCT24, null));
        DateCheck theirs = seedCheck(otherOrg, null, OCT24, 1);
        Event e = events.findById(id).orElseThrow();
        e.setDateCheckId(theirs.getId());
        events.save(e);

        prediction(id).andExpect(status().isOk()).andExpect(jsonPath("$.dateCheck").doesNotExist());
    }

    @Test
    void noLinkOmitsDateCheck() throws Exception {
        UUID id = createdEvent(eventBody(OCT24, null));

        prediction(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("none"))
                .andExpect(jsonPath("$.dateCheck").doesNotExist());
    }

    @Test
    void checkWithoutDatesOmitsDateCheck() throws Exception {
        DateCheck c = seedCheck(org, null);
        UUID id = createdEvent(eventBody(OCT24, c.getId()));

        prediction(id).andExpect(status().isOk()).andExpect(jsonPath("$.dateCheck").doesNotExist());
    }

    // --- gate closed: the link is invisible and cannot be written, while subGenre stays ungated ---

    @Nested
    class GateOff {

        @BeforeEach
        void close() {
            flips.set(props, "enabled", false);
        }

        private Event seedEvent(UUID dateCheckId) {
            Event e = new Event();
            e.setOrgId(org.getId());
            e.setCreatedBy(owner.getId());
            e.setName("Gate Off Night");
            e.setSlug("ev-" + UUID.randomUUID());
            e.setStatus(EventStatus.DRAFT);
            e.setStartsAt(DEC5.atTime(20, 0).toInstant(ZoneOffset.UTC));
            e.setDateCheckId(dateCheckId);
            return events.save(e);
        }

        @Test
        void gateOffOmitsDateCheck() throws Exception {
            DateCheck c = seedCheck(org, null, DEC5, 1);
            Event e = seedEvent(c.getId());
            c.setEventId(e.getId());
            checks.save(c);

            prediction(e.getId()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("none"))
                    .andExpect(jsonPath("$.dateCheck").doesNotExist());
        }

        @Test
        void gateOffCreateWithDateCheckIdIs404() throws Exception {
            DateCheck c = seedCheck(org, null, DEC5, 1);

            mvc.perform(post("/api/v1/events").with(authentication(mine())).contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "X", "dateCheckId", c.getId().toString()))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.error.message").value("Date check not found"));

            assertThat(ownEvents()).isZero();
            assertThat(checks.findById(c.getId()).orElseThrow().getEventId()).isNull();
        }

        @Test
        void gateOffSubGenreStillAccepted() throws Exception {
            Event e = seedEvent(null);

            patchEvent(e.getId(), Map.of("subGenre", "techno")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.subGenre").value("techno"));

            assertThat(events.findById(e.getId()).orElseThrow().getSubGenre()).isEqualTo("techno");
        }
    }
}
