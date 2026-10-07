package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.AiGenerationUsage;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.model.PredictionFeedback;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.repository.PredictionFeedbackRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.Stage0Scorer;
import com.imin.iminapi.repository.AiGenerationUsageRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PredictorRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The frozen endpoint contract end-to-end: 202 to ready through the real Stage-0 scorer over the faked ChatClient,
 * input-hash cache short-circuit (no second LLM call, no second ledger row), org scoping (cross-org 404), kind=score
 * quota 429, feedback write, and the global kill switch.
 */
@IminIntegrationTest
class PredictionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired PropertyFlips flips;
    @Autowired PredictorProperties predictorProps;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatClient chatClient;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired PredictionFeedbackRepository feedback;
    @Autowired AiGenerationUsageRepository usage;

    final ObjectMapper om = new ObjectMapper();
    private final String city = "Amsterdam" + DateCheckControllerTest.letters();
    private final List<UUID> createdOrgs = new ArrayList<>();

    private Organization org;
    private User owner;
    private Event event;

    @BeforeEach
    void seed() {
        org = fx.org();
        createdOrgs.add(org.getId());
        org.setCountry("NL");
        org = orgs.save(org);
        owner = fx.owner(org);

        event = newDraft(org, owner, "Predictor Night");

        // The real scorer's one call: prompt().options(..).user(..).call().entity(Stage0Output.class).
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.options(any())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(call);
        when(call.entity(Stage0Scorer.Stage0Output.class)).thenReturn(validOutput());
    }

    @AfterEach
    void after() {
        PredictorRows.delete(jdbc, createdOrgs);
    }

    private long ownCount(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE org_id = ?", Long.class, org.getId());
    }

    private List<PredictionLedger> ownLedger() {
        return ledger.findByEventIdOrderByCreatedAtDesc(event.getId());
    }

    private List<PredictionFeedback> ownFeedback() {
        return feedback.findAll().stream().filter(f -> event.getId().equals(f.getEventId())).toList();
    }

    private Event newDraft(Organization o, User u, String name) {
        Event e = new Event();
        e.setOrgId(o.getId());
        e.setCreatedBy(u.getId());
        e.setName(name);
        e.setSlug("ev-" + UUID.randomUUID().toString().substring(0, 8));
        e.setGenre("techno");
        e.setVenueCity(city);
        e.setVenueCountry("NL");
        e.setTimezone("Europe/Amsterdam");
        e.setStartsAt(Instant.now().plusSeconds(45L * 86400));
        e = events.save(e);
        TicketTier early = new TicketTier();
        early.setEventId(e.getId());
        early.setName("Early");
        early.setPriceMinor(1500);
        early.setQuantity(50);
        tiers.save(early);
        TicketTier door = new TicketTier();
        door.setEventId(e.getId());
        door.setName("Door");
        door.setPriceMinor(2400);
        door.setQuantity(200);
        door.setSortOrder(1);
        tiers.save(door);
        return e;
    }

    private static Stage0Scorer.Stage0Output validOutput() {
        return new Stage0Scorer.Stage0Output(
                new Stage0Scorer.RawBand(35, 60),
                new Stage0Scorer.RawRange(120, 210),
                new Stage0Scorer.RawLongRange(120 * 1500L, 210 * 2400L),
                List.of(new PredictionResult.Factor("Saturday in summer", "supporting", "comparable Saturdays outperform"),
                        new PredictionResult.Factor("Prices inside band", "supporting", "tier prices within comparable range"),
                        new PredictionResult.Factor("Low own history", "opposing", "organizer has few completed events")),
                List.of(new Stage0Scorer.RecCandidate("earlybird-price", "Consider a lower Early Bird",
                        "comparable early tiers priced lower", "HIGH", "tier_edit", "Early", 1200, null)));
    }

    private Authentication auth(User u, Organization o) {
        AuthPrincipal p = new AuthPrincipal(u.getId(), o.getId(), UserRole.OWNER, UUID.randomUUID());
        return new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    private JsonNode pollUntilNotPending(Authentication a, UUID eventId) throws Exception {
        for (int i = 0; i < 100; i++) {
            MvcResult res = mvc.perform(get("/api/v1/events/" + eventId + "/prediction").with(authentication(a)))
                    .andExpect(status().isOk()).andReturn();
            JsonNode body = om.readTree(res.getResponse().getContentAsString());
            if (!"pending".equals(body.get("status").asText())) return body;
            Thread.sleep(50);
        }
        throw new AssertionError("prediction stayed pending");
    }

    @Test
    void triggerFlowScoresLedgersAndCaches() throws Exception {
        Authentication a = auth(owner, org);

        // Nothing yet
        mvc.perform(get("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("none"));

        // Trigger → 202 pending
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.predictionId").isNotEmpty());

        JsonNode ready = pollUntilNotPending(a, event.getId());
        assertThat(ready.get("status").asText()).isEqualTo("ready");
        assertThat(ready.get("inputHash").asText()).hasSize(64);
        JsonNode result = ready.get("result");
        assertThat(result.get("confidenceTier").asText()).isEqualTo("C"); // empty corpus → tier C
        assertThat(result.get("selloutBand").get("lowPct").asInt()).isEqualTo(35);
        assertThat(result.get("benchmarkOnly").asBoolean()).isFalse();
        assertThat(result.get("factors")).hasSize(3);

        // Write-before-render: exactly one ledger row, hash matches
        assertThat(ownLedger()).hasSize(1);
        assertThat(ownLedger().get(0).getInputSnapshotHash()).isEqualTo(ready.get("inputHash").asText());

        // Unchanged draft → cached 200, NO second LLM call, NO new ledger row, NO quota burn
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.result.selloutBand.highPct").value(60));
        verify(chatClient, times(1)).prompt();
        assertThat(ownLedger()).hasSize(1);
        assertThat(ownCount("ai_generation_usage")).isEqualTo(1); // only the first (real) run consumed kind=score
    }

    @Test
    void crossOrgEventIs404() throws Exception {
        Organization other = fx.org();
        createdOrgs.add(other.getId());
        User outsider = fx.owner(other);

        Authentication foreign = auth(outsider, other);
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction").with(authentication(foreign)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/events/" + event.getId() + "/prediction").with(authentication(foreign)))
                .andExpect(status().isNotFound());
        assertThat(ownLedger()).isEmpty();
    }

    @Test
    void scoreQuotaExhaustedReturns429Envelope() throws Exception {
        for (int i = 0; i < 50; i++) { // default AI_QUOTA_SCORE_PER_DAY
            AiGenerationUsage u = new AiGenerationUsage();
            u.setUserId(owner.getId());
            u.setOrgId(org.getId());
            u.setKind("score");
            u.setCreatedAt(Instant.now());
            usage.save(u);
        }
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction")
                        .with(authentication(auth(owner, org))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AI_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.error.fields.limit").value("50"));
        assertThat(ownLedger()).isEmpty(); // nothing scored, nothing ledgered
    }

    @Test
    void feedbackPersistsAgainstLatestLedgerRow() throws Exception {
        Authentication a = auth(owner, org);
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isAccepted());
        pollUntilNotPending(a, event.getId());

        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction/feedback")
                        .with(authentication(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendationId\":\"earlybird-price\",\"type\":\"dismissed\"}"))
                .andExpect(status().isNoContent());

        assertThat(ownFeedback()).hasSize(1);
        var fb = ownFeedback().get(0);
        assertThat(fb.getRecommendationId()).isEqualTo("earlybird-price");
        assertThat(fb.getLedgerId()).isEqualTo(ownLedger().get(0).getId());
    }

    @Test
    void dismissalMemoryFiltersRecommendationAtServeTimeAndRestoreBringsItBack() throws Exception {
        Authentication a = auth(owner, org);
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isAccepted());
        pollUntilNotPending(a, event.getId());

        // Before dismissal: the recommendation is served.
        mvc.perform(get("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.recommendations.length()").value(1))
                .andExpect(jsonPath("$.result.recommendations[0].impact").value("HIGH"));

        // Dismiss it → filtered out at serve time, dismissedCount surfaces the "remembered" row.
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction/feedback").with(authentication(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendationId\":\"earlybird-price\",\"type\":\"dismissed\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.recommendations.length()").value(0))
                .andExpect(jsonPath("$.dismissedCount").value(1));

        // Restore it → dismissal cleared, recommendation returns.
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction/feedback").with(authentication(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendationId\":\"earlybird-price\",\"type\":\"restored\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.recommendations.length()").value(1))
                .andExpect(jsonPath("$.dismissedCount").value(0));
    }

    /**
     * predictor-edge-12: an id absent from the render this feedback targets cannot yield a
     * fingerprint, so the row that used to be written suppressed nothing and the 204 claimed a
     * suppression that never happened — the recommendation came back on the next load with no
     * error shown. It is a 404 now, and nothing is persisted.
     */
    @Test
    void dismissingAnIdThatIsNotInTheRenderIsNotFound() throws Exception {
        Authentication a = auth(owner, org);
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isAccepted());
        pollUntilNotPending(a, event.getId());

        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction/feedback")
                        .with(authentication(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendationId\":\"stale-from-an-older-render\",\"type\":\"dismissed\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(ownFeedback()).isEmpty();
    }

    static Stream<Arguments> refusedFeedback() {
        return Stream.of(
                // No render to rate yet.
                Arguments.of("{\"recommendationId\":\"x\",\"type\":\"executed\"}", 409, null),
                // Over-long ids are a field-named 400, not a DB-constraint 400.
                Arguments.of("{\"recommendationId\":\"" + "z".repeat(129) + "\",\"type\":\"dismissed\"}", 400, null),
                Arguments.of("{\"type\":\"dismissed\"}", 400, "required"));
    }

    /** Refused feedback writes nothing; a 400 names the recommendationId field (a null code only requires it). */
    @ParameterizedTest
    @MethodSource("refusedFeedback")
    void refusedFeedbackWritesNothing(String body, int status, String code) throws Exception {
        ResultActions r = mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction/feedback")
                        .with(authentication(auth(owner, org)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(status));
        if (status == 400) {
            r.andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
            if (code == null) r.andExpect(jsonPath("$.error.fields.recommendationId").exists());
            else r.andExpect(jsonPath("$.error.fields.recommendationId").value(code));
        }
        assertThat(ownFeedback()).isEmpty();
    }

    /** Kill switch: benchmark-only for every request, no LLM call, no quota burn, and still ledgered. */
    @Test
    void killSwitchServesBenchmarkOnlyWithoutLlmOrQuota() throws Exception {
        flips.set(predictorProps, "benchmarkOnly", true);
        // No timezone and no tiers: the benchmark-only path must still build a snapshot for a bare draft.
        Event bare = new Event();
        bare.setOrgId(org.getId());
        bare.setCreatedBy(owner.getId());
        bare.setName("Dark Mode Night");
        bare.setSlug("ks-" + UUID.randomUUID());
        bare.setGenre("techno");
        bare.setVenueCity(city);
        bare.setVenueCountry("NL");
        bare.setStartsAt(Instant.now().plusSeconds(30L * 86400));
        event = events.save(bare);
        long usageBefore = ownCount("ai_generation_usage");
        long ledgerBefore = ownLedger().size();
        Authentication a = auth(owner, org);

        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isAccepted());
        JsonNode body = pollUntilNotPending(a, event.getId());

        assertThat(body.get("status").asText()).isEqualTo("failed_benchmark_only");
        JsonNode result = body.get("result");
        assertThat(result.get("benchmarkOnly").asBoolean()).isTrue();
        assertThat(result.has("selloutBand")).isFalse();      // NO forward numbers
        assertThat(result.has("attendanceRange")).isFalse();
        assertThat(result.has("revenueRangeMinor")).isFalse();
        assertThat(result.get("comparables")).isNotNull();    // corpus stats still served

        verifyNoInteractions(chatClient);                      // no LLM call
        assertThat(ownCount("ai_generation_usage") - usageBefore).isZero();   // no quota burn
        assertThat(ownLedger().size() - ledgerBefore).isEqualTo(1);           // still ledgered
    }
}
