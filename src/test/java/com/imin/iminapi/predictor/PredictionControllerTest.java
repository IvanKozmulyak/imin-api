package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.AiGenerationUsage;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.repository.PredictionFeedbackRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.Stage0Scorer;
import com.imin.iminapi.repository.AiGenerationUsageRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The frozen endpoint contract end-to-end (task 86cav4766 BE half): 202→ready flow with a
 * mocked Stage-0 scorer, input-hash cache short-circuit (no second LLM call, no second
 * ledger row), org scoping (cross-org 404), kind=score quota 429, feedback write.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class PredictionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired PredictionFeedbackRepository feedback;
    @Autowired AiGenerationUsageRepository usage;

    @MockitoBean Stage0Scorer scorer;

    final ObjectMapper om = new ObjectMapper();

    private Organization org;
    private User owner;
    private Event event;

    @BeforeEach
    void seed() {
        clean();
        org = new Organization();
        org.setName("Predictor Org");
        org.setSlug("predictor-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("p@example.test");
        org.setCountry("NL");
        org = orgs.save(org);

        owner = new User();
        owner.setOrgId(org.getId());
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = newDraft(org, owner, "Predictor Night");

        when(scorer.modelId()).thenReturn("test/model");
        when(scorer.score(any(), any(), any())).thenReturn(validOutput());
    }

    @AfterEach
    void after() { clean(); }

    private void clean() {
        feedback.deleteAll();
        ledger.deleteAll();
        usage.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private Event newDraft(Organization o, User u, String name) {
        Event e = new Event();
        e.setOrgId(o.getId());
        e.setCreatedBy(u.getId());
        e.setName(name);
        e.setSlug("ev-" + UUID.randomUUID().toString().substring(0, 8));
        e.setGenre("techno");
        e.setVenueCity("Amsterdam");
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
                new PredictionResult.Band(35, 60),
                new PredictionResult.Range(120, 210),
                new PredictionResult.LongRange(120 * 1500L, 210 * 2400L),
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
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(ledger.findAll().get(0).getInputSnapshotHash()).isEqualTo(ready.get("inputHash").asText());

        // Unchanged draft → cached 200, NO second LLM call, NO new ledger row, NO quota burn
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.result.selloutBand.highPct").value(60));
        verify(scorer, times(1)).score(any(), any(), any());
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(usage.count()).isEqualTo(1); // only the first (real) run consumed kind=score
    }

    @Test
    void crossOrgEventIs404() throws Exception {
        Organization other = new Organization();
        other.setName("Other Org");
        other.setSlug("other-" + UUID.randomUUID().toString().substring(0, 8));
        other.setContactEmail("o@example.test");
        other.setCountry("DE");
        other = orgs.save(other);
        User outsider = new User();
        outsider.setOrgId(other.getId());
        outsider.setEmail("outsider-" + UUID.randomUUID() + "@example.test");
        outsider.setRole(UserRole.OWNER);
        outsider = users.save(outsider);

        Authentication foreign = auth(outsider, other);
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction").with(authentication(foreign)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/events/" + event.getId() + "/prediction").with(authentication(foreign)))
                .andExpect(status().isNotFound());
        assertThat(ledger.count()).isZero();
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
        assertThat(ledger.count()).isZero(); // nothing scored, nothing ledgered
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

        assertThat(feedback.count()).isEqualTo(1);
        var fb = feedback.findAll().get(0);
        assertThat(fb.getRecommendationId()).isEqualTo("earlybird-price");
        assertThat(fb.getLedgerId()).isEqualTo(ledger.findAll().get(0).getId());
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

    @Test
    void feedbackWithoutAnyPredictionIsConflict() throws Exception {
        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction/feedback")
                        .with(authentication(auth(owner, org)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recommendationId\":\"x\",\"type\":\"executed\"}"))
                .andExpect(status().isConflict());
    }
}
