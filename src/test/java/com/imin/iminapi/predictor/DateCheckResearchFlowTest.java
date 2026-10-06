package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.jobs.PredictorJobRunner;
import com.imin.iminapi.predictor.jobs.PredictorJobService;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.PredictorJob;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckFindingRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import com.imin.iminapi.predictor.research.DateCheckResearchJobHandler;
import com.imin.iminapi.predictor.research.DateCheckResearchSweeper;
import com.imin.iminapi.predictor.research.ResearchCache;
import com.imin.iminapi.predictor.research.ResearchLlmClient;
import com.imin.iminapi.predictor.research.ResearchLlmClient.Citation;
import com.imin.iminapi.predictor.research.ResearchLlmClient.Reply;
import com.imin.iminapi.predictor.research.ResearchLlmClient.Usage;
import com.imin.iminapi.predictor.research.WebResearchService;
import com.imin.iminapi.predictor.service.DateCheckService;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Check a date" with web research on H2, the research call mocked, the clock fixed at 1 Oct 2026 10:00 UTC. Orgs A
 * and C are in the date-check beta, B is not; caps are 2 per org and 3 in total per UTC day.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = {"imin.predictor.date-check.enabled=true",
        "imin.predictor.date-check.beta-org-ids=" + DateCheckResearchFlowTest.ORG_A + ","
                + DateCheckResearchFlowTest.ORG_C,
        "imin.predictor.date-check.research-daily-cap-per-org=2",
        "imin.predictor.date-check.research-daily-cap-global=3"})
class DateCheckResearchFlowTest {

    static final String ORG_A = "aaaaaaaa-0000-0000-0000-00000000000a";
    static final String ORG_B = "aaaaaaaa-0000-0000-0000-00000000000b";
    static final String ORG_C = "aaaaaaaa-0000-0000-0000-00000000000c";
    private static final String BASE = "/api/v1/predictions/date-checks";
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate OCT17 = LocalDate.of(2026, 10, 17);
    private static final LocalDate DEC5 = LocalDate.of(2026, 12, 5);
    private static final String URL = "https://www.infoconcert.com/amelie-lens-rex-club.html";
    private static final String QUOTE = "Amelie Lens au Rex Club le samedi 17 octobre 2026";
    private static final Usage USAGE = new Usage(4300, 250, 1, new BigDecimal("0.0125"));

    @TestBean Clock clock;

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @MockitoBean ResearchLlmClient client;
    @MockitoSpyBean DateCheckService service;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;
    @Autowired DateCheckFindingRepository findings;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired PredictorJobRepository jobs;
    @Autowired PredictorJobRunner runner;
    @Autowired DateCheckResearchJobHandler handler;
    @Autowired ResearchCache cache;
    @Autowired DateCheckResearchSweeper sweeper;
    @Autowired PredictorJobService jobService;
    @Autowired PredictorProperties props;

    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, User> owners = new LinkedHashMap<>();

    @BeforeEach
    void seed() {
        clean();
        for (String id : List.of(ORG_A, ORG_B, ORG_C)) {
            jdbc.update("insert into organizations (id, name, slug, contact_email, country) "
                    + "values (?, 'Research Org', ?, 'r@example.test', 'FR')", UUID.fromString(id), "rs-" + id);
            User u = new User();
            u.setOrgId(UUID.fromString(id));
            u.setEmail("owner-" + UUID.randomUUID() + "@example.test");
            u.setRole(UserRole.OWNER);
            owners.put(id, users.save(u));
        }
        when(client.research(anyString(), anyString(), anyString())).thenReturn(ok());
        // The cache bean outlives a test; start each one without another test's answers.
        cache.clear();
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
        checks.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private static Reply ok() {
        String json = """
                {"findings":[{"title":"Amelie Lens - Rex Club","type":"same_genre_event","url":"%s","quote":"%s",
                 "strength":3}]}""".formatted(URL, QUOTE);
        return new Reply(json, true, "stop",
                List.of(new Citation(URL, "Techno à Paris", "Agenda. " + QUOTE + ", 23h.")), USAGE);
    }

    private Authentication as(String org) {
        AuthPrincipal p = new AuthPrincipal(owners.get(org).getId(), UUID.fromString(org), UserRole.OWNER,
                UUID.randomUUID());
        return new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    private static Map<String, Object> body(boolean research) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("city", "Paris");
        b.put("genreFamily", "house & techno");
        b.put("dates", List.of(OCT17.toString(), DEC5.toString()));
        b.put("research", research);
        return b;
    }

    private ResultActions postCheck(String org, Map<String, Object> b) throws Exception {
        return mvc.perform(post(BASE).with(authentication(as(org))).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(b)));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode fetch(String org, String id) throws Exception {
        return json(mvc.perform(get(BASE + "/" + id).with(authentication(as(org)))).andExpect(status().isOk()));
    }

    private static JsonNode date(JsonNode check, LocalDate d) {
        for (JsonNode n : check.get("dates")) if (n.get("date").asText().equals(d.toString())) return n;
        throw new AssertionError("no date " + d);
    }

    private static List<JsonNode> web(JsonNode date) {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode f : date.get("findings")) if (f.get("sourceKind").asText().equals("web")) out.add(f);
        return out;
    }

    /** A check queued for research before today (UTC); it must not count against today's caps. */
    private void queuedYesterday(String org) {
        DateCheck c = new DateCheck();
        c.setOrgId(UUID.fromString(org));
        c.setCreatedBy(owners.get(org).getId());
        c.setCity("Paris");
        c.setCountry("FR");
        c.setGenreFamily("house & techno");
        c.setStatus("done");
        c.setQuestionBankVersion("qb5-gp1");
        c.setResearch(true);
        c.setResearchStatus(DateCheck.RESEARCH_DONE);
        c.setResearchQueuedAt(Instant.parse("2026-09-30T23:59:59Z"));
        checks.save(c);
    }

    // --- queue and finish ---

    @Test
    void researchQueuesAJobAndAnswers202WithTheCalendarResult() throws Exception {
        JsonNode r = json(postCheck(ORG_A, body(true)).andExpect(status().isAccepted()));

        assertThat(r.get("status").asText()).isEqualTo("running");
        assertThat(r.get("research").asBoolean()).isTrue();
        assertThat(r.get("researchStatus").asText()).isEqualTo("running");
        assertThat(r.get("dates")).hasSize(2);
        assertThat(web(date(r, OCT17))).isEmpty();
        DateCheck stored = checks.findById(UUID.fromString(r.get("id").asText())).orElseThrow();
        assertThat(stored.getResearchQueuedAt()).isEqualTo(NOW);
        List<PredictorJob> queued = jobs.findAll();
        assertThat(queued).singleElement().satisfies(j -> {
            assertThat(j.getKind()).isEqualTo("date_check_research");
            assertThat(om.readTree(j.getPayloadJson()).get("dateCheckId").asText()).isEqualTo(r.get("id").asText());
        });
        assertThat(ledger.count()).isEqualTo(1);
        verify(client, never()).research(anyString(), anyString(), anyString());
    }

    @Test
    void jobMergesWebFindingsAndFinishesTheCheck() throws Exception {
        JsonNode before = json(postCheck(ORG_A, body(true)).andExpect(status().isAccepted()));
        String id = before.get("id").asText();

        runner.tick();

        JsonNode after = fetch(ORG_A, id);
        assertThat(after.get("status").asText()).isEqualTo("done");
        assertThat(after.get("researchStatus").asText()).isEqualTo("done");
        assertThat(web(date(after, OCT17))).singleElement().satisfies(f -> {
            assertThat(f.get("questionId").asText()).isEqualTo("2.1");
            assertThat(f.get("status").asText()).isEqualTo("found");
            assertThat(f.get("strength").asInt()).isEqualTo(2);
            assertThat(f.get("weight").asInt()).isEqualTo(3);
            assertThat(f.get("stopFactor").asBoolean()).isFalse();
            assertThat(f.get("templateKey").asText()).isEqualTo("predictor.q.2_1");
            assertThat(f.get("url").asText()).isEqualTo(URL);
            assertThat(f.get("quote").asText()).isEqualTo(QUOTE);
            assertThat(Instant.parse(f.get("fetchedAt").asText())).isEqualTo(NOW);
            assertThat(f.get("facts").get("name").asText()).isEqualTo("Amelie Lens");
            assertThat(f.get("facts").get("date").asText()).isEqualTo("2026-10-17");
        });
        assertThat(web(date(after, DEC5))).isEmpty();
        // The web risk is scored: min(4, strength 2 x weight 3) on top of the calendar result.
        assertThat(date(after, OCT17).get("riskScore").asInt())
                .isEqualTo(Math.min(10, date(before, OCT17).get("riskScore").asInt() + 4));
        assertThat(date(after, DEC5).get("riskScore").asInt()).isEqualTo(date(before, DEC5).get("riskScore").asInt());
        assertThat(jobs.findAll()).singleElement().extracting(PredictorJob::getStatus).isEqualTo("done");
    }

    @Test
    void costRecorded() throws Exception {
        String id = json(postCheck(ORG_A, body(true))).get("id").asText();

        runner.tick();

        List<PredictionLedger> rows = ledger.findAll().stream()
                .filter(l -> !"rules/date-check".equals(l.getModelId())).toList();
        assertThat(rows).singleElement().satisfies(l -> {
            assertThat(l.getSurface()).isEqualTo(PredictionSurface.DATE_CHECK);
            assertThat(l.getDateCheckId()).isEqualTo(UUID.fromString(id));
            assertThat(l.getEventId()).isNull();
            assertThat(l.getModelId()).isEqualTo("anthropic/claude-haiku-4.5");
            assertThat(l.getPromptVersion()).isEqualTo("research-1");
            assertThat(l.getQuestionBankVersion()).isEqualTo("qb5-gp1");
            assertThat(l.getTokensIn()).isEqualTo(4300);
            assertThat(l.getTokensOut()).isEqualTo(250);
            assertThat(l.getSearches()).isEqualTo(1);
            assertThat(l.getCostUsd()).isEqualByComparingTo("0.0125");
        });
        assertThat(ledger.count()).isEqualTo(2);
    }

    @Test
    void twoPagesOnTheSameEventCountOnce() throws Exception {
        String other = "https://www.sortiraparis.com/amelie-lens-rex-club";
        String quote = "Amelie Lens au Rex Club le samedi 5 décembre 2026";
        String json = """
                {"findings":[
                 {"title":"Amelie Lens - Rex Club","type":"same_genre_event","url":"%s","quote":"%s","strength":2},
                 {"title":"Amelie Lens - Rex Club","type":"same_genre_event","url":"%s","quote":"%s","strength":2}]}"""
                .formatted(URL, quote, other, quote);
        when(client.research(anyString(), anyString(), anyString())).thenReturn(new Reply(json, true, "stop",
                List.of(new Citation(URL, "Techno à Paris", "Agenda. " + quote + ", 23h."),
                        new Citation(other, "Sortir à Paris", quote + ". Billets en vente.")), USAGE));
        JsonNode before = json(postCheck(ORG_A, body(true)).andExpect(status().isAccepted()));
        int calendarRisk = date(before, DEC5).get("riskScore").asInt();

        runner.tick();

        JsonNode night = date(fetch(ORG_A, before.get("id").asText()), DEC5);
        assertThat(web(night)).singleElement().satisfies(f -> assertThat(f.get("questionId").asText())
                .isEqualTo("2.1"));
        // One web finding adds at most 4 points; two would reach the move threshold of 7.
        assertThat(calendarRisk).isLessThan(3);
        assertThat(night.get("riskScore").asInt()).isEqualTo(calendarRisk + 4);
        assertThat(night.get("verdict").asText()).isNotEqualTo("move");
    }

    @Test
    void onePageReportedAsBothTypesCountsOnce() throws Exception {
        String quote = "Amelie Lens au Rex Club le samedi 5 décembre 2026";
        String bigQuote = "Rex Club: Amelie Lens, samedi 5 décembre 2026, complet";
        String json = """
                {"findings":[
                 {"title":"Amelie Lens - Rex Club","type":"big_event","url":"%1$s","quote":"%2$s","strength":2},
                 {"title":"Amelie Lens - Rex Club","type":"same_genre_event","url":"%1$s","quote":"%2$s","strength":2},
                 {"title":"Amelie Lens - Rex Club","type":"big_event","url":"%1$s","quote":"%3$s","strength":2}]}"""
                .formatted(URL, quote, bigQuote);
        when(client.research(anyString(), anyString(), anyString())).thenReturn(new Reply(json, true, "stop",
                List.of(new Citation(URL, "Techno à Paris", "Agenda. " + quote + ". " + bigQuote + ".")), USAGE));
        JsonNode before = json(postCheck(ORG_A, body(true)).andExpect(status().isAccepted()));
        int calendarRisk = date(before, DEC5).get("riskScore").asInt();

        runner.tick();

        JsonNode night = date(fetch(ORG_A, before.get("id").asText()), DEC5);
        assertThat(web(night)).singleElement().satisfies(f -> assertThat(f.get("questionId").asText())
                .isEqualTo("2.1"));
        // 2.1 and 5.3 from one page would add 8 and reach the move threshold of 7.
        assertThat(calendarRisk).isLessThan(3);
        assertThat(night.get("riskScore").asInt()).isEqualTo(calendarRisk + 4);
        assertThat(night.get("verdict").asText()).isNotEqualTo("move");
    }

    @Test
    void cacheHitLedgersZeroUsage() throws Exception {
        String id = json(postCheck(ORG_A, body(true))).get("id").asText();
        WebResearchService.Outcome hit = new WebResearchService.Outcome(true, Map.of(OCT17, List.of()), null,
                "anthropic/claude-haiku-4.5", null);

        assertThat(service.completeResearch(UUID.fromString(id), hit)).isTrue();

        List<PredictionLedger> rows = ledger.findAll().stream()
                .filter(l -> !"rules/date-check".equals(l.getModelId())).toList();
        assertThat(rows).singleElement().satisfies(l -> {
            assertThat(l.getDateCheckId()).isEqualTo(UUID.fromString(id));
            assertThat(l.getModelId()).isEqualTo("anthropic/claude-haiku-4.5");
            assertThat(l.getPromptVersion()).isEqualTo("research-1");
            assertThat(l.getTokensIn()).isZero();
            assertThat(l.getTokensOut()).isZero();
            assertThat(l.getSearches()).isZero();
            assertThat(l.getCostUsd()).isEqualByComparingTo("0");
        });
        assertThat(fetch(ORG_A, id).get("researchStatus").asText()).isEqualTo("done");
    }

    @Test
    void leaseExpiredOnTheLastAttemptMarksResearchFailed() throws Exception {
        String id = json(postCheck(ORG_A, body(true)).andExpect(status().isAccepted())).get("id").asText();
        // A runner died mid-call on the last attempt: the lease ran out and nothing reported the failure.
        jdbc.update("update predictor_job set status = 'running', attempts = ?, locked_until = ?",
                PredictorJobService.MAX_ATTEMPTS, java.sql.Timestamp.from(NOW.minusSeconds(60)));

        runner.tick();

        assertThat(jobs.findAll()).singleElement().satisfies(j -> {
            assertThat(j.getStatus()).isEqualTo("failed");
            assertThat(j.getLastError()).isEqualTo("lock expired");
        });
        DateCheck after = checks.findById(UUID.fromString(id)).orElseThrow();
        assertThat(after.getResearchStatus()).isEqualTo("failed");
        assertThat(after.getStatus()).isEqualTo("done");
        verify(client, never()).research(anyString(), anyString(), anyString());
    }

    @Test
    void providerTimeoutGivesPartialResult() throws Exception {
        when(client.research(anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("Read timed out"));
        JsonNode before = json(postCheck(ORG_A, body(true)).andExpect(status().isAccepted()));

        runner.tick();

        JsonNode after = fetch(ORG_A, before.get("id").asText());
        assertThat(after.get("status").asText()).isEqualTo("done");
        assertThat(after.get("researchStatus").asText()).isEqualTo("failed");
        assertThat(after.get("research").asBoolean()).isTrue();
        assertThat(after.get("dates")).isEqualTo(before.get("dates"));
        assertThat(jobs.findAll()).singleElement().extracting(PredictorJob::getStatus).isEqualTo("done");
        verify(client, times(1)).research(anyString(), anyString(), anyString());
        assertThat(ledger.count()).isEqualTo(1);
    }

    @Test
    void paidCallWithAnUnusableAnswerIsLedgeredAndFails() throws Exception {
        when(client.research(anyString(), anyString(), anyString()))
                .thenReturn(new Reply("{\"findings\":[", true, "stop", ok().citations(), USAGE));
        String id = json(postCheck(ORG_A, body(true))).get("id").asText();

        runner.tick();

        assertThat(fetch(ORG_A, id).get("researchStatus").asText()).isEqualTo("failed");
        List<PredictionLedger> rows = ledger.findAll().stream()
                .filter(l -> !"rules/date-check".equals(l.getModelId())).toList();
        assertThat(rows).singleElement().satisfies(l -> {
            assertThat(l.getDateCheckId()).isEqualTo(UUID.fromString(id));
            assertThat(l.getPromptVersion()).isEqualTo("research-1");
            assertThat(l.getTokensIn()).isEqualTo(4300);
            assertThat(l.getCostUsd()).isEqualByComparingTo("0.0125");
            assertThat(l.getOutputJson()).contains("\"researchStatus\":\"failed\"", "\"reason\":\"parse\"");
        });
    }

    @Test
    void organizerFieldsNeverInQuery() throws Exception {
        Event e = new Event();
        e.setOrgId(UUID.fromString(ORG_A));
        e.setCreatedBy(owners.get(ORG_A).getId());
        e.setName("Gala Zebrafish Night");
        e.setSlug("ev-" + UUID.randomUUID().toString().substring(0, 8));
        e.setGenre("house & techno");
        e.setVenueCity("Paris");
        e.setVenueCountry("FR");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(OCT17.atTime(22, 0).toInstant(ZoneOffset.UTC));
        e.setStatus(EventStatus.DRAFT);
        e = events.save(e);
        Map<String, Object> b = body(true);
        b.put("postalCode", "75011");
        b.put("eventId", e.getId().toString());
        b.put("capacity", 4321);
        b.put("priceMinor", 8765);
        b.put("format", "warehouse");
        b.put("lineup", List.of("DJ Quokkalicious"));
        b.put("knownEvents", List.of(Map.of("name", "Rival Narwhal Bash", "date", OCT17.toString(),
                "venue", "Le Hangar Mystere", "strength", 2)));
        b.put("audienceAge", List.of(25, 35));
        b.put("communities", List.of("PT"));
        b.put("buyingLeadDays", 19);
        b.put("subGenre", "techno");
        postCheck(ORG_A, b).andExpect(status().isAccepted());

        runner.tick();

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(client).research(anyString(), system.capture(), user.capture());
        String sent = system.getValue() + "\n" + user.getValue();
        assertThat(sent).contains("Paris, France", "house & techno (techno)", "2026-10-10", "2026-12-12");
        assertThat(sent).doesNotContain("75011", "Gala Zebrafish", e.getId().toString(), "4321", "8765", "warehouse",
                "Quokkalicious", "Narwhal", "Hangar", "Research Org", "r@example.test", ORG_A,
                owners.get(ORG_A).getEmail());
    }

    // --- stuck research sweep ---

    private String queueResearch() throws Exception {
        return json(postCheck(ORG_A, body(true)).andExpect(status().isAccepted())).get("id").asText();
    }

    private void queuedAgo(String id, Duration ago) {
        jdbc.update("update date_check set research_queued_at = ? where id = ?",
                java.sql.Timestamp.from(NOW.minus(ago)), UUID.fromString(id));
    }

    private String researchStatus(String id) {
        return checks.findById(UUID.fromString(id)).orElseThrow().getResearchStatus();
    }

    @Test
    void unknownKindFailedAtMaxAttemptsIsSweptToFailed() throws Exception {
        String id = queueResearch();
        queuedAgo(id, Duration.ofMinutes(2));
        jdbc.update("update predictor_job set attempts = ?", PredictorJobService.MAX_ATTEMPTS - 1);
        // An older build without the research handler releases the job on its last attempt.
        new PredictorJobRunner(jobService, jobs, List.of(), new PredictorProperties()).tick();

        assertThat(jobs.findAll()).singleElement().satisfies(j -> {
            assertThat(j.getStatus()).isEqualTo("failed");
            assertThat(j.getLastError()).isEqualTo("unknown kind: date_check_research");
        });
        assertThat(researchStatus(id)).isEqualTo("running");

        assertThat(sweeper.sweep()).isEqualTo(1);

        DateCheck after = checks.findById(UUID.fromString(id)).orElseThrow();
        assertThat(after.getResearchStatus()).isEqualTo("failed");
        assertThat(after.getStatus()).isEqualTo("done");
        verify(client, never()).research(anyString(), anyString(), anyString());
    }

    @Test
    void tickDiedBeforeTerminalCleanupIsSweptToFailed() throws Exception {
        String id = queueResearch();
        queuedAgo(id, Duration.ofMinutes(2));
        jdbc.update("update predictor_job set status = 'running', attempts = ?, locked_until = ?",
                PredictorJobService.MAX_ATTEMPTS, java.sql.Timestamp.from(NOW.minusSeconds(60)));
        // The requeue commits; the handler clean-up that would follow never runs.
        jobService.requeueExpired();

        assertThat(jobs.findAll()).singleElement().extracting(PredictorJob::getStatus).isEqualTo("failed");
        assertThat(researchStatus(id)).isEqualTo("running");

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(researchStatus(id)).isEqualTo("failed");
    }

    @Test
    void liveJobKeepsResearchRunning() throws Exception {
        String id = queueResearch();
        queuedAgo(id, Duration.ofMinutes(49));

        assertThat(sweeper.sweep()).isZero();
        assertThat(researchStatus(id)).isEqualTo("running");

        jdbc.update("update predictor_job set status = 'running', attempts = 1, locked_until = ?",
                java.sql.Timestamp.from(NOW.plus(PredictorJobService.LOCK)));
        assertThat(sweeper.sweep()).isZero();
        assertThat(researchStatus(id)).isEqualTo("running");
    }

    @Test
    void overdueResearchFailsEvenWithALiveJobAndTheJobThenMakesNoCall() throws Exception {
        String id = queueResearch();
        queuedAgo(id, Duration.ofMinutes(50).plusSeconds(1));

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(researchStatus(id)).isEqualTo("failed");

        runner.tick();

        assertThat(jobs.findAll()).singleElement().extracting(PredictorJob::getStatus).isEqualTo("done");
        verify(client, never()).research(anyString(), anyString(), anyString());
    }

    @Test
    void orphanWithinTheGraceIsLeftRunning() throws Exception {
        String id = queueResearch();
        jdbc.update("delete from predictor_job");
        queuedAgo(id, Duration.ofSeconds(59));

        assertThat(sweeper.sweep()).isZero();
        assertThat(researchStatus(id)).isEqualTo("running");

        queuedAgo(id, Duration.ofSeconds(61));
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(researchStatus(id)).isEqualTo("failed");
    }

    @Test
    void finishedResearchIsNotTouched() {
        queuedYesterday(ORG_A);
        for (String research : List.of(DateCheck.RESEARCH_FAILED, DateCheck.RESEARCH_OFF)) {
            DateCheck c = new DateCheck();
            c.setOrgId(UUID.fromString(ORG_A));
            c.setCreatedBy(owners.get(ORG_A).getId());
            c.setCity("Paris");
            c.setCountry("FR");
            c.setGenreFamily("house & techno");
            c.setStatus("done");
            c.setQuestionBankVersion("qb5-gp1");
            c.setResearch(!research.equals(DateCheck.RESEARCH_OFF));
            c.setResearchStatus(research);
            c.setResearchQueuedAt(research.equals(DateCheck.RESEARCH_OFF) ? null : NOW.minusSeconds(7200));
            checks.save(c);
        }

        assertThat(sweeper.sweep()).isZero();

        assertThat(checks.findAll()).extracting(DateCheck::getResearchStatus)
                .containsExactlyInAnyOrder("done", "failed", "off");
        assertThat(checks.findAll()).extracting(DateCheck::getStatus).containsOnly("done");
    }

    @Test
    void pollSkipsWhileJobsPollIsOff() throws Exception {
        String id = queueResearch();
        jdbc.update("delete from predictor_job");
        queuedAgo(id, Duration.ofMinutes(2));
        assertThat(props.isJobsPollEnabled()).isFalse();

        sweeper.poll();
        assertThat(researchStatus(id)).isEqualTo("running");

        sweeper.sweep();
        assertThat(researchStatus(id)).isEqualTo("failed");
    }

    @Test
    void unreadableJobPayloadIsIgnored() throws Exception {
        String id = queueResearch();
        queuedAgo(id, Duration.ofMinutes(2));
        jdbc.update("update predictor_job set payload_json = '{}'");

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(researchStatus(id)).isEqualTo("failed");
    }

    // --- gate and caps ---

    @Test
    void closedDateCheckGateQueuesNoResearch() throws Exception {
        postCheck(ORG_B, body(true)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/config").with(authentication(as(ORG_B)))).andExpect(status().isNotFound());

        assertThat(checks.count()).isZero();
        assertThat(jobs.count()).isZero();
        mvc.perform(get(BASE + "/config").with(authentication(as(ORG_A))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.researchAvailable").value(true));
        verify(client, never()).research(anyString(), anyString(), anyString());
    }

    @Test
    void researchNotAskedForStaysOff() throws Exception {
        JsonNode r = json(postCheck(ORG_A, body(false)).andExpect(status().isOk()));

        assertThat(r.get("research").asBoolean()).isFalse();
        assertThat(r.get("researchStatus").asText()).isEqualTo("off");
        assertThat(jobs.count()).isZero();
    }

    @Test
    void perOrgCapReachedFailsWithoutACall() throws Exception {
        queuedYesterday(ORG_A);
        postCheck(ORG_A, body(true)).andExpect(status().isAccepted());
        postCheck(ORG_A, body(true)).andExpect(status().isAccepted());

        JsonNode third = json(postCheck(ORG_A, body(true)).andExpect(status().isOk()));

        assertThat(third.get("research").asBoolean()).isTrue();
        assertThat(third.get("researchStatus").asText()).isEqualTo("failed");
        assertThat(third.get("status").asText()).isEqualTo("done");
        assertThat(third.get("dates")).hasSize(2);
        assertThat(checks.findById(UUID.fromString(third.get("id").asText())).orElseThrow().getResearchQueuedAt())
                .isNull();
        assertThat(jobs.count()).isEqualTo(2);
        // Another org is still under its own cap.
        postCheck(ORG_C, body(true)).andExpect(status().isAccepted());
        verify(client, never()).research(anyString(), anyString(), anyString());
    }

    @Test
    void globalCapReachedFailsWithoutACall() throws Exception {
        postCheck(ORG_A, body(true)).andExpect(status().isAccepted());
        postCheck(ORG_A, body(true)).andExpect(status().isAccepted());
        postCheck(ORG_C, body(true)).andExpect(status().isAccepted());

        JsonNode fourth = json(postCheck(ORG_C, body(true)).andExpect(status().isOk()));

        assertThat(fourth.get("researchStatus").asText()).isEqualTo("failed");
        assertThat(jobs.count()).isEqualTo(3);
    }

    @Test
    void dateCheckClosedBeforeTheJobRunsFailsWithoutACall() throws Exception {
        // A row queued for B while B had access: the job re-checks the gate and makes no call.
        DateCheck c = new DateCheck();
        c.setOrgId(UUID.fromString(ORG_B));
        c.setCreatedBy(owners.get(ORG_B).getId());
        c.setCity("Paris");
        c.setCountry("FR");
        c.setGenreFamily("house & techno");
        c.setStatus("running");
        c.setQuestionBankVersion("qb5-gp1");
        c.setResearch(true);
        c.setResearchStatus(DateCheck.RESEARCH_RUNNING);
        c.setResearchQueuedAt(NOW);
        c = checks.save(c);
        DateCheckDate night = new DateCheckDate();
        night.setDateCheckId(c.getId());
        night.setCandidateDate(OCT17);
        night.setVerdict("good");
        night.setRiskScore((short) 0);
        night.setOppScore((short) 0);
        night.setCoverage(BigDecimal.ONE);
        checkDates.save(night);
        jdbc.update("insert into predictor_job (id, kind, payload_json, status, attempts, run_after, created_at, "
                        + "updated_at) values (?, 'date_check_research', ?, 'queued', 0, ?, ?, ?)", UUID.randomUUID(),
                "{\"dateCheckId\":\"" + c.getId() + "\"}", java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));

        runner.tick();

        DateCheck after = checks.findById(c.getId()).orElseThrow();
        assertThat(after.getResearchStatus()).isEqualTo("failed");
        assertThat(after.getStatus()).isEqualTo("done");
        verify(client, never()).research(anyString(), anyString(), anyString());
    }

    // --- idempotency, failure and later writers ---

    @Test
    void repeatedJobMakesNoSecondCall() throws Exception {
        String id = json(postCheck(ORG_A, body(true))).get("id").asText();
        runner.tick();
        PredictorJob again = jobs.findAll().get(0);
        // Without the cache a second call would be paid, so only the running check guard can stop it.
        cache.clear();

        handler.run(again);

        verify(client, times(1)).research(anyString(), anyString(), anyString());
        assertThat(web(date(fetch(ORG_A, id), OCT17))).hasSize(1);
        assertThat(ledger.count()).isEqualTo(2);
    }

    @Test
    void completeResearchOnAFinishedCheckChangesNothingButLedgersThePaidCall() throws Exception {
        String id = json(postCheck(ORG_A, body(true))).get("id").asText();
        assertThat(service.failResearch(UUID.fromString(id))).isTrue();
        JsonNode failed = fetch(ORG_A, id);
        WebResearchService.Outcome late = new WebResearchService.Outcome(true,
                Map.of(OCT17, List.of()), USAGE, "anthropic/claude-haiku-4.5", null);
        WebResearchService.Outcome lateHit = new WebResearchService.Outcome(true,
                Map.of(OCT17, List.of()), null, "anthropic/claude-haiku-4.5", null);

        assertThat(service.completeResearch(UUID.fromString(id), late)).isFalse();
        assertThat(service.completeResearch(UUID.fromString(id), lateHit)).isFalse();
        assertThat(service.failResearch(UUID.fromString(id))).isFalse();

        assertThat(fetch(ORG_A, id)).isEqualTo(failed);
        assertThat(failed.get("researchStatus").asText()).isEqualTo("failed");
        // The late paid call is counted; the late cache hit spent nothing and writes no row.
        List<PredictionLedger> rows = ledger.findAll().stream()
                .filter(l -> !"rules/date-check".equals(l.getModelId())).toList();
        assertThat(rows).singleElement().satisfies(l -> {
            assertThat(l.getDateCheckId()).isEqualTo(UUID.fromString(id));
            assertThat(l.getModelId()).isEqualTo("anthropic/claude-haiku-4.5");
            assertThat(l.getPromptVersion()).isEqualTo("research-1");
            assertThat(l.getTokensIn()).isEqualTo(4300);
            assertThat(l.getTokensOut()).isEqualTo(250);
            assertThat(l.getSearches()).isEqualTo(1);
            assertThat(l.getCostUsd()).isEqualByComparingTo("0.0125");
            assertThat(l.getOutputJson()).contains("\"researchStatus\":\"failed\"", "\"reason\":\"late\"");
        });
        assertThat(ledger.count()).isEqualTo(2);
    }

    @Test
    void lastAttemptFailureMarksResearchFailedAndKeepsTheError() throws Exception {
        String id = json(postCheck(ORG_A, body(true))).get("id").asText();
        PredictorJob job = jobs.findAll().get(0);
        RuntimeException boom = new IllegalStateException("db down");
        doThrow(boom).when(service).completeResearch(any(), any());

        job.setAttempts(1);
        assertThatThrownBy(() -> handler.run(job)).isSameAs(boom);
        assertThat(checks.findById(UUID.fromString(id)).orElseThrow().getResearchStatus()).isEqualTo("running");

        job.setAttempts(PredictorJobService.MAX_ATTEMPTS);
        assertThatThrownBy(() -> handler.run(job)).isSameAs(boom);
        DateCheck after = checks.findById(UUID.fromString(id)).orElseThrow();
        assertThat(after.getResearchStatus()).isEqualTo("failed");
        assertThat(after.getStatus()).isEqualTo("done");
    }

    @Test
    void failingCleanupDoesNotReplaceTheOriginalError() throws Exception {
        json(postCheck(ORG_A, body(true)));
        PredictorJob job = jobs.findAll().get(0);
        job.setAttempts(PredictorJobService.MAX_ATTEMPTS);
        RuntimeException boom = new IllegalStateException("db down");
        RuntimeException cleanup = new IllegalStateException("still down");
        doThrow(boom).when(service).completeResearch(any(), any());
        doThrow(cleanup).when(service).failResearch(any());

        assertThatThrownBy(() -> handler.run(job)).isSameAs(boom)
                .satisfies(t -> assertThat(t.getSuppressed()).containsExactly(cleanup));
    }

    @Test
    void patchAssumptionsKeepsWebFindings() throws Exception {
        String id = json(postCheck(ORG_A, body(true))).get("id").asText();
        runner.tick();
        JsonNode done = fetch(ORG_A, id);

        JsonNode p = json(mvc.perform(patch(BASE + "/" + id + "/assumptions").with(authentication(as(ORG_A)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"buyingLeadDays\":14}")).andExpect(status().isOk()));

        assertThat(p.get("researchStatus").asText()).isEqualTo("done");
        assertThat(web(date(p, OCT17))).isEqualTo(web(date(done, OCT17)));
        assertThat(date(p, OCT17).get("riskScore")).isEqualTo(date(done, OCT17).get("riskScore"));
        verify(client, times(1)).research(anyString(), anyString(), anyString());
    }

    @Test
    void openApiDocumentsThe202() throws Exception {
        JsonNode docs = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()));
        JsonNode responses = docs.path("paths").path(BASE).path("post").path("responses");
        for (String code : List.of("200", "202")) {
            JsonNode content = responses.path(code).path("content");
            assertThat(content.size()).as(code).isPositive();
            content.forEach(media -> assertThat(media.path("schema").path("$ref").asText()).as(code)
                    .endsWith("/DateCheckResponse"));
        }
    }
}
