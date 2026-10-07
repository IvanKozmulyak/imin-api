package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PredictorRows;
import com.imin.iminapi.support.PropertyFlips;
import com.imin.iminapi.support.RecordingRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Check a date" end-to-end with the clock pinned at 1 Oct 2026. Postal code 75011 is school zone C;
 * the seeded calendar makes 24 Oct a Toussaint-break Saturday and 5 Dec a plain one. Each test has its own city.
 */
@IminIntegrationTest
class DateCheckControllerTest {

    private static final String BASE = "/api/v1/predictions/date-checks";
    private static final LocalDate BREAK = LocalDate.of(2026, 10, 24);
    private static final LocalDate PLAIN = LocalDate.of(2026, 12, 5);
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final String BUCKET = "predictor-date-check";

    @Autowired MutableClock clock;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired RecordingRateLimiter rateLimiter;
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired ReferenceCalendarEntryRepository calendar;
    @Autowired DateCheckProperties props;
    @Autowired QuestionBank bank;

    private final ObjectMapper om = new ObjectMapper();
    private final String city = "Paris" + letters();
    private final List<UUID> createdOrgs = new ArrayList<>();
    private final List<UUID> calendarRows = new ArrayList<>();

    private Organization org;
    private User owner;
    private Organization otherOrg;
    private User otherOwner;

    @BeforeEach
    void seed() {
        clock.setInstant(NOW);
        org = org("FR");
        owner = fx.owner(org);
        otherOrg = org("FR");
        otherOwner = fx.owner(otherOrg);
        flips.set(props, "enabled", true);
        flips.set(props, "betaOrgIds", Set.of(org.getId(), otherOrg.getId()));

        cal("holiday", "", "2026-11-11", null, "Armistice");
        cal("pont", "", "2026-05-15", null, "Pont de l'Ascension");
        cal("hijri", "", "2026-03-20", null, "eid_al_fitr");
        cal("school", "FR-ZC", "2026-10-17", "2026-11-01", "Vacances de la Toussaint");
    }

    @AfterEach
    void after() {
        try {
            calendar.deleteAllById(calendarRows);
        } finally {
            PredictorRows.delete(jdbc, createdOrgs);
        }
    }

    static String letters() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) b.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        return b.toString();
    }

    private Organization org(String country) {
        Organization o = fx.org();
        createdOrgs.add(o.getId());
        o.setCountry(country);
        return orgs.save(o);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, org.getId());
    }

    private long ownChecks() {
        return count("SELECT count(*) FROM date_check WHERE org_id = ?");
    }

    private long ownCheckDates() {
        return count("SELECT count(*) FROM date_check_date d JOIN date_check c ON c.id = d.date_check_id"
                + " WHERE c.org_id = ?");
    }

    private long ownFindings() {
        return count("SELECT count(*) FROM date_check_finding f JOIN date_check_date d ON d.id = f.date_check_date_id"
                + " JOIN date_check c ON c.id = d.date_check_id WHERE c.org_id = ?");
    }

    private long ownLedger() {
        return count("SELECT count(*) FROM prediction_ledger WHERE org_id = ?");
    }

    private long ownJobs() {
        return count("SELECT count(*) FROM predictor_job j WHERE EXISTS (SELECT 1 FROM date_check c"
                + " WHERE c.org_id = ? AND j.payload_json LIKE '%' || c.id::text || '%')");
    }

    /** Calls on the date-check bucket, for one key or (null) any. */
    private long consumed(String key) {
        return rateLimiter.calls().stream()
                .filter(c -> c.bucket().equals(BUCKET) && (key == null || c.key().equals(key))).count();
    }

    private void cal(String kind, String region, String from, String to, String name) {
        ReferenceCalendarEntry e = new ReferenceCalendarEntry();
        e.setCountry("FR");
        e.setRegion(region);
        e.setCalendarDate(LocalDate.parse(from));
        e.setEndDate(to == null ? null : LocalDate.parse(to));
        e.setKind(kind);
        e.setName(name);
        e.setSourceUrl("https://example.org/" + kind);
        e.setSyncedAt(Instant.parse("2026-09-01T00:00:00Z"));
        calendarRows.add(calendar.save(e).getId());
    }

    private static Authentication auth(User u, Organization o) {
        AuthPrincipal p = new AuthPrincipal(u.getId(), o.getId(), UserRole.OWNER, UUID.randomUUID());
        return new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    private Authentication mine() {
        return auth(owner, org);
    }

    private Map<String, Object> body(LocalDate... dates) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("city", city);
        b.put("postalCode", "75011");
        b.put("genreFamily", "house & techno");
        b.put("dates", List.of(dates).stream().map(LocalDate::toString).toList());
        b.put("capacity", 300);
        b.put("knownEvents", List.of());
        return b;
    }

    private ResultActions postCheck(Authentication a, Map<String, Object> b) throws Exception {
        return mvc.perform(post(BASE).with(authentication(a)).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(b)));
    }

    private JsonNode created(Map<String, Object> b) throws Exception {
        String json = postCheck(mine(), b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    private static JsonNode date(JsonNode check, LocalDate d) {
        for (JsonNode n : check.get("dates")) if (n.get("date").asText().equals(d.toString())) return n;
        throw new AssertionError("no date " + d);
    }

    // --- gate and scoping ---

    @Test
    void betaOffIs404() throws Exception {
        flips.set(props, "enabled", false);
        postCheck(mine(), body(PLAIN)).andExpect(status().isNotFound());
        mvc.perform(get(BASE).with(authentication(mine()))).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/config").with(authentication(mine()))).andExpect(status().isNotFound());

        flips.set(props, "enabled", true);
        flips.set(props, "betaOrgIds", Set.of(otherOrg.getId()));
        postCheck(mine(), body(PLAIN)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(ownChecks()).isZero();
    }

    @Test
    void otherOrgCheckIs404() throws Exception {
        String id = created(body(PLAIN)).get("id").asText();
        Authentication theirs = auth(otherOwner, otherOrg);

        mvc.perform(get(BASE + "/" + id).with(authentication(theirs))).andExpect(status().isNotFound());
        mvc.perform(patch(BASE + "/" + id + "/assumptions").with(authentication(theirs))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"communities\":[]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/" + UUID.randomUUID()).with(authentication(mine()))).andExpect(status().isNotFound());
        assertThat(ownLedger()).isEqualTo(1);
    }

    @Test
    void postAndPatchEachConsumeTheUserBucketAfterTheGate() throws Exception {
        String id = created(body(PLAIN)).get("id").asText();
        assertThat(consumed(owner.getId().toString())).isEqualTo(1);

        mvc.perform(patch(BASE + "/" + id + "/assumptions").with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"communities\":[]}"))
                .andExpect(status().isOk());
        assertThat(consumed(owner.getId().toString())).isEqualTo(2);

        flips.set(props, "enabled", false);
        postCheck(mine(), body(PLAIN)).andExpect(status().isNotFound());
        mvc.perform(patch(BASE + "/" + id + "/assumptions").with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"communities\":[]}"))
                .andExpect(status().isNotFound());
        assertThat(consumed(null)).isEqualTo(2);
    }

    @Test
    void gateOffNeverConsumes() throws Exception {
        flips.set(props, "enabled", false);
        postCheck(mine(), body(PLAIN)).andExpect(status().isNotFound());
        mvc.perform(patch(BASE + "/" + UUID.randomUUID() + "/assumptions").with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertThat(consumed(null)).isZero();
    }

    @Test
    void softDeletedEventIdIs404() throws Exception {
        Event gone = event(org, owner, PLAIN);
        // deleted_at is not updatable through the entity; only a bulk write sets it.
        jdbc.update("UPDATE events SET deleted_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.parse("2026-09-30T00:00:00Z")), gone.getId());
        Map<String, Object> b = body(PLAIN);
        b.put("eventId", gone.getId().toString());

        postCheck(mine(), b).andExpect(status().isNotFound());
        assertThat(ownChecks()).isZero();
    }

    @Test
    void patchOnACheckWithAPastDateIs422Stale() throws Exception {
        DateCheck old = rawCheck(org, owner, Instant.parse("2026-09-01T00:00:00Z"));
        com.imin.iminapi.predictor.model.DateCheckDate d = new com.imin.iminapi.predictor.model.DateCheckDate();
        d.setDateCheckId(old.getId());
        d.setCandidateDate(LocalDate.of(2026, 9, 20));
        d.setVerdict("good");
        d.setCoverage(new java.math.BigDecimal("0.800"));
        checkDates.save(d);

        mvc.perform(patch(BASE + "/" + old.getId() + "/assumptions").with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"communities\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.check").value("past"))
                .andExpect(jsonPath("$.error.fields['dates[0]']").doesNotExist());
        assertThat(ownLedger()).isZero();
    }

    @Test
    void foreignEventIdIs404() throws Exception {
        Event theirs = event(otherOrg, otherOwner, PLAIN);
        Map<String, Object> b = body(PLAIN);
        b.put("eventId", theirs.getId().toString());

        postCheck(mine(), b).andExpect(status().isNotFound());
        assertThat(ownChecks()).isZero();
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body(PLAIN))))
                .andExpect(status().isUnauthorized());
    }

    // --- validation ---

    @Test
    void duplicateDatesAre422() throws Exception {
        postCheck(mine(), body(PLAIN, PLAIN)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.dates").value("duplicate"));
    }

    @Test
    void pastDateIs422() throws Exception {
        postCheck(mine(), body(LocalDate.of(2026, 9, 30))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields['dates[0]']").value("past"));
    }

    @Test
    void sixDatesAre422() throws Exception {
        LocalDate[] six = new LocalDate[6];
        for (int i = 0; i < 6; i++) six[i] = PLAIN.plusDays(i);
        postCheck(mine(), body(six)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.dates").value("too_many"));
        assertThat(ownChecks()).isZero();
        assertThat(ownLedger()).isZero();
    }

    @Test
    void countryFallsBackToOrgAndIs422WithoutOne() throws Exception {
        assertThat(created(body(PLAIN)).get("country").asText()).isEqualTo("FR");

        Organization bare = org("");
        flips.set(props, "betaOrgIds", Set.of(bare.getId()));
        postCheck(auth(fx.owner(bare), bare), body(PLAIN)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.country").value("required"));
    }

    // --- run ---

    @Test
    void syncCheckReturnsRankedDates() throws Exception {
        JsonNode r = created(body(BREAK, PLAIN));

        assertThat(r.get("status").asText()).isEqualTo("done");
        assertThat(r.get("postalCode").asText()).isEqualTo("75011");
        assertThat(r.get("dates")).hasSize(2);
        JsonNode inBreak = date(r, BREAK);
        JsonNode plain = date(r, PLAIN);
        assertThat(plain.get("rank").asInt()).isEqualTo(1);
        assertThat(inBreak.get("rank").asInt()).isEqualTo(2);
        assertThat(inBreak.get("riskScore").asInt()).isGreaterThan(plain.get("riskScore").asInt());
        assertThat(inBreak.get("verdict").asText()).isEqualTo("adjust");
        assertThat(plain.get("verdict").asText()).isEqualTo("good");
        assertThat(inBreak.get("coverageBucket").asText()).isEqualTo("high");
        assertThat(inBreak.get("checkedCount").asInt()).isEqualTo(8);
        assertThat(inBreak.get("applicableCount").asInt()).isEqualTo(10);

        JsonNode school = null;
        for (JsonNode f : inBreak.get("findings")) if (f.get("questionId").asText().equals("7.1")) school = f;
        assertThat(school).isNotNull();
        assertThat(school.get("status").asText()).isEqualTo("found");
        assertThat(school.get("kind").asText()).isEqualTo("risk");
        assertThat(school.get("sourceKind").asText()).isEqualTo("structured");
        assertThat(school.get("templateKey").asText()).isEqualTo("predictor.q.7_1");
        assertThat(school.get("facts").get("endDate").asText()).isEqualTo("2026-11-01");
        assertThat(inBreak.get("breakdown").get(0).get("questionId").asText()).isEqualTo("7.1");
        assertThat(inBreak.get("breakdown").get(0).get("points").asInt()).isEqualTo(4);

        List<String> notChecked = new ArrayList<>();
        for (JsonNode n : inBreak.get("notChecked")) notChecked.add(n.get("questionId").asText() + ":" + n.get("reason").asText());
        assertThat(notChecked).contains("5.1:no_source", "3.2:source_off", "9.1:source_off",
                "2.6:no_source", "5.3:no_source", "2.3:no_source", "2.1:no_imin_events_in_city");
        // The break's early-promo action would be due 26 Sep, already past on 1 Oct, so it is not offered.
        assertThat(inBreak.get("actions").isArray()).isTrue();
        assertThat(inBreak.get("actions")).isEmpty();

        // A stored check reads back identically.
        String again = mvc.perform(get(BASE + "/" + r.get("id").asText()).with(authentication(mine())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(om.readTree(again)).isEqualTo(r);
    }

    /** The open-events catalogue knows Paris, so its gated-off sources read source_off rather than no_source. */
    @Test
    void knownCityWithGatedOffOpenEventSourcesReadsSourceOff() throws Exception {
        Map<String, Object> paris = body(PLAIN);
        paris.put("city", "Paris");
        JsonNode plain = date(created(paris), PLAIN);

        List<String> notChecked = new ArrayList<>();
        for (JsonNode n : plain.get("notChecked")) notChecked.add(n.get("questionId").asText() + ":" + n.get("reason").asText());
        assertThat(notChecked).contains("2.6:source_off", "5.3:source_off", "2.3:source_off");
    }

    @Test
    void ledgerRowWrittenBeforeResponse() throws Exception {
        JsonNode r = created(body(BREAK, PLAIN));

        List<PredictionLedger> rows = ledger.findAll().stream().filter(l -> org.getId().equals(l.getOrgId())).toList();
        assertThat(rows).hasSize(1);
        PredictionLedger row = rows.get(0);
        assertThat(row.getSurface()).isEqualTo(PredictionSurface.DATE_CHECK);
        assertThat(row.getEventId()).isNull();
        assertThat(row.getOrgId()).isEqualTo(org.getId());
        assertThat(row.getDateCheckId()).isEqualTo(UUID.fromString(r.get("id").asText()));
        assertThat(row.getQuestionBankVersion()).isEqualTo(bank.version());
        assertThat(r.get("questionBankVersion").asText()).isEqualTo(bank.version());
        assertThat(row.getInputSnapshotHash()).hasSize(64);
        JsonNode out = om.readTree(row.getOutputJson());
        assertThat(out.get("dates")).hasSize(2);
        assertThat(out.get("dates").get(0).get("date").asText()).isEqualTo(BREAK.toString());
        assertThat(out.get("dates").get(0).get("rank").asInt()).isEqualTo(2);
    }

    @Test
    void ledgerFailureRollsBackCheck() throws Exception {
        try (PgFaults.Fault ledgerDown = PgFaults.failWrites(jdbc, "prediction_ledger", "org_id", org.getId())) {
            postCheck(mine(), body(PLAIN)).andExpect(status().isInternalServerError());
        }

        assertThat(ownChecks()).isZero();
        assertThat(ownCheckDates()).isZero();
        assertThat(ownFindings()).isZero();
    }

    @Test
    void eventIdStoredAndExcludedFromOwnEventMatches() throws Exception {
        Event self = event(org, owner, PLAIN);
        JsonNode without = created(body(PLAIN));
        assertThat(finding(date(without, PLAIN), "2.7").get("status").asText()).isEqualTo("found");

        Map<String, Object> b = body(PLAIN);
        b.put("eventId", self.getId().toString());
        JsonNode with = created(b);

        assertThat(with.get("eventId").asText()).isEqualTo(self.getId().toString());
        assertThat(finding(date(with, PLAIN), "2.7").get("status").asText()).isEqualTo("clear");
        assertThat(checks.findById(UUID.fromString(with.get("id").asText())).orElseThrow().getEventId())
                .isEqualTo(self.getId());
    }

    private static JsonNode finding(JsonNode date, String questionId) {
        for (JsonNode f : date.get("findings")) if (f.get("questionId").asText().equals(questionId)) return f;
        throw new AssertionError("no finding " + questionId);
    }

    private Event event(Organization o, User u, LocalDate night) {
        Event e = new Event();
        e.setOrgId(o.getId());
        e.setCreatedBy(u.getId());
        e.setName("Own night");
        e.setSlug("ev-" + UUID.randomUUID());
        e.setGenre("house & techno");
        e.setVenueCity(city);
        e.setVenueCountry("FR");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(night.atTime(22, 0).toInstant(ZoneOffset.UTC));
        e.setStatus(EventStatus.DRAFT);
        return events.save(e);
    }

    // --- patch ---

    @Test
    void patchAssumptionsRescoresWithoutResearch() throws Exception {
        JsonNode r = created(body(BREAK, PLAIN));
        String id = r.get("id").asText();

        String json = mvc.perform(patch(BASE + "/" + id + "/assumptions").with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communities\":[],\"audienceAge\":[25,40],\"priceMinor\":1500,\"startHour\":22,\"buyingLeadDays\":14}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode p = om.readTree(json);

        assertThat(ownLedger()).isEqualTo(2);
        assertThat(ownJobs()).isZero();
        assertThat(p.get("researchStatus").asText()).isEqualTo("off");
        assertThat(p.get("priceMinor").asLong()).isEqualTo(1500);
        Map<String, JsonNode> byField = new LinkedHashMap<>();
        for (JsonNode a : p.get("assumptions")) byField.put(a.get("field").asText(), a);
        assertThat(byField.get("communities").get("source").asText()).isEqualTo("organizer");
        assertThat(byField.get("communities").get("value")).isEmpty();
        assertThat(byField.get("audienceAge").get("value").toString()).isEqualTo("[25,40]");
        assertThat(byField.get("priceMinor").get("value").toString()).isEqualTo("[1500,1500]");
        assertThat(byField.get("startHour").get("value").asInt()).isEqualTo(22);
        assertThat(byField.get("buyingLeadDays").get("source").asText()).isEqualTo("organizer");
        assertThat(p.get("dates")).hasSize(2);
        assertThat(checkDates.findByDateCheckIdOrderByCandidateDateAsc(UUID.fromString(id))).hasSize(2);
        assertThat(ownCheckDates()).isEqualTo(2);

        // A later patch keeps earlier organizer answers it does not touch.
        String json2 = mvc.perform(patch(BASE + "/" + id + "/assumptions").with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"buyingLeadDays\":10}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, JsonNode> after = new LinkedHashMap<>();
        for (JsonNode a : om.readTree(json2).get("assumptions")) after.put(a.get("field").asText(), a);
        assertThat(after.get("buyingLeadDays").get("value").asInt()).isEqualTo(10);
        assertThat(after.get("audienceAge").get("value").toString()).isEqualTo("[25,40]");
        assertThat(after.get("communities").get("source").asText()).isEqualTo("organizer");
        assertThat(ownLedger()).isEqualTo(3);
    }

    @Test
    void patchValidationIs422() throws Exception {
        String id = created(body(PLAIN)).get("id").asText();

        mvc.perform(patch(BASE + "/" + id + "/assumptions").with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"startHour\":24}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.startHour").value("out_of_range"));
        assertThat(ownLedger()).isEqualTo(1);
    }

    // --- list ---

    @Test
    void listIsOrgScopedNewestFirstAndClamped() throws Exception {
        for (int i = 0; i < 51; i++) rawCheck(org, owner, Instant.parse("2026-09-01T00:00:00Z").plusSeconds(i));
        DateCheck theirs = rawCheck(otherOrg, otherOwner, Instant.parse("2026-09-30T00:00:00Z"));

        String json = mvc.perform(get(BASE).param("limit", "500").with(authentication(mine())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode list = om.readTree(json);

        assertThat(list).hasSize(50);
        assertThat(list.get(0).get("createdAt").asText()).isEqualTo("2026-09-01T00:00:50Z");
        for (JsonNode n : list) assertThat(n.get("id").asText()).isNotEqualTo(theirs.getId().toString());
        mvc.perform(get(BASE).param("limit", "0").with(authentication(mine())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(BASE).with(authentication(mine())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(20));
    }

    @Test
    void listSummaryCarriesDates() throws Exception {
        created(body(BREAK, PLAIN));

        mvc.perform(get(BASE).with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].city").value(city))
                .andExpect(jsonPath("$[0].dates[0].date").value(BREAK.toString()))
                .andExpect(jsonPath("$[0].dates[0].verdict").value("adjust"))
                .andExpect(jsonPath("$[0].dates[0].rank").value(2))
                .andExpect(jsonPath("$[0].dates[1].rank").value(1));
    }

    private DateCheck rawCheck(Organization o, User u, Instant createdAt) {
        DateCheck c = new DateCheck();
        c.setOrgId(o.getId());
        c.setCreatedBy(u.getId());
        c.setCity(city);
        c.setCountry("FR");
        c.setGenreFamily("house & techno");
        c.setStatus("done");
        c.setQuestionBankVersion(bank.version());
        c.setCreatedAt(createdAt);
        c.setUpdatedAt(createdAt);
        return checks.save(c);
    }

    // --- contract ---

    @Test
    void responseHasNoRenderedStrings() throws Exception {
        Map<String, Object> b = body(BREAK, PLAIN);
        b.put("knownEvents", List.of(Map.of("name", "Rival night", "date", PLAIN.toString(), "strength", 2)));
        JsonNode r = created(b);
        Set<String> keys = bank.templateKeys();
        Set<String> banned = Set.of("text", "label", "message", "sentence", "title", "description");
        List<String> templateKeys = new ArrayList<>();

        walk(r, (name, node) -> {
            assertThat(banned).as("key %s", name).doesNotContain(name);
            if (node.isTextual() && node.asText().startsWith("predictor.")) {
                assertThat(keys).as("rendered or unknown key %s", node.asText()).contains(node.asText());
            }
            if (name.equals("templateKey") || name.equals("key")) templateKeys.add(node.asText());
        });

        assertThat(templateKeys).isNotEmpty().allSatisfy(k -> assertThat(keys).contains(k));
        assertThat(templateKeys).contains("predictor.q.2_1", "predictor.q.7_1", "predictor.a.competitor_differentiate");
        JsonNode action = date(r, PLAIN).get("actions").get(0);
        assertThat(action.get("key").asText()).isEqualTo("predictor.a.competitor_differentiate");
        assertThat(action.get("dueDate").asText()).isEqualTo("2026-11-14");
        assertThat(action.get("questionId").asText()).isEqualTo("2.1");
        assertThat(action.get("params").get("name").asText()).isEqualTo("Rival night");
    }

    private interface Visitor { void visit(String name, JsonNode node); }

    private static void walk(JsonNode node, Visitor v) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                v.visit(e.getKey(), e.getValue());
                walk(e.getValue(), v);
            }
        } else if (node.isArray()) {
            for (JsonNode c : node) walk(c, v);
        }
    }
}
