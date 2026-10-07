package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.PlanPopulation;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.PlanRefreshExecutor;
import com.imin.iminapi.audienceplan.config.SummaryChatClient;
import com.imin.iminapi.audienceplan.config.SummaryExecutor;
import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse;
import com.imin.iminapi.marketing.service.MomentumTriggered;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan GET → async summary → stored per locale on the same plan row. The summary ChatClient is the shared fake,
 * answering with the recorded OpenRouter contents; nothing reaches the network.
 */
@IminIntegrationTest
class SummarizerFlowTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final String HOUSE = "house & techno";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired TicketTierRepository tierRepo;
    @Autowired AudiencePlanProperties props;
    @Autowired PlanService planService;
    @Autowired Summarizer summarizer;
    @Autowired @Qualifier(SummaryExecutor.NAME) Executor summaryExecutor;
    @Autowired @Qualifier(PlanRefreshExecutor.NAME) Executor refreshExecutor;
    @Autowired ApplicationEventPublisher events;
    @Autowired AudiencePlanLogic logic;
    @Autowired Clock clock;
    @Autowired PropertyFlips flips;
    @Autowired @Qualifier(SummaryChatClient.NAME) ChatClient chat;

    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec call;
    private UUID orgId;
    private AuthPrincipal owner;

    @BeforeEach
    void setUp() {
        flips.set(props, "summaryEnabled", true);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(spec);
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        Organization o = new Organization();
        o.setName("Summary Org");
        o.setSlug("summary-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("summary@example.com");
        o.setCountry("FR");
        o.setTimezone("Europe/Paris");
        orgId = orgRepo.save(o).getId();
        owner = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.MEMBER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        AsyncDrain.drain(refreshExecutor);
        AsyncDrain.drain(summaryExecutor);
        jdbc.update("delete from audience_plan_segments where plan_id in (select id from audience_plans where org_id = ?)", orgId);
        jdbc.update("update audience_plans set superseded_by = null where org_id = ?", orgId);
        jdbc.update("delete from audience_plans where org_id = ?", orgId);
        List<UUID> consumers = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, orgId);
        jdbc.update("delete from memberships where org_id = ?", orgId);
        for (UUID c : consumers) jdbc.update("delete from consumers where consumer_id = ?", c);
        jdbc.update("delete from ticket_tiers where event_id in (select id from events where org_id = ?)", orgId);
        jdbc.update("delete from events where org_id = ?", orgId);
        jdbc.update("delete from users where org_id = ?", orgId);
        jdbc.update("delete from organizations where id = ?", orgId);
    }

    @Test
    void get_answersNull_thenStoresTheLocalesSummary_readBackOnTheNextGet() throws Exception {
        answer("undated-en.json");
        Event e = event();

        String id = id(getPlan(e, null).andExpect(jsonPath("$.summary").value(nullValue())));
        drain();

        getPlan(e, "en").andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.summary.headline")
                        .value("Your list could bring 25–95 of the 255 tickets you are aiming for."))
                .andExpect(jsonPath("$.summary.segmentLines.length()").value(3))
                .andExpect(jsonPath("$.summary.locale").value("en"))
                .andExpect(jsonPath("$.summary.aiGenerated").value(true))
                .andExpect(jsonPath("$.summary.aiDisclosure").value("mode=ai-originated"))
                .andExpect(jsonPath("$.summary.model").value("openai/gpt-4o-mini-2024-07-18"))
                .andExpect(jsonPath("$.versions.model").value("openai/gpt-4o-mini-2024-07-18"));
        Map<String, Object> row = row(id);
        assertThat(row.get("tokens_in")).isEqualTo(1210);
        assertThat(row.get("tokens_out")).isEqualTo(190);
        // Default model and prices (Haiku 4.5, $1 / $5 per Mtok): 1210 × 1 + 190 × 5 = 2160 → 0.00216 → 0.0022.
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.0022");
        drain();
        verify(chat, times(1)).prompt();
    }

    @Test
    void secondLocale_addsAKeyToTheSamePlanRow() throws Exception {
        answer("undated-en.json", "undated-fr.json");
        Event e = event();

        String id = id(getPlan(e, "en"));
        drain();
        assertThat(id(getPlan(e, "fr").andExpect(jsonPath("$.summary").value(nullValue())))).isEqualTo(id);
        drain();

        getPlan(e, "fr").andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.summary.locale").value("fr"))
                .andExpect(jsonPath("$.summary.headline").value("Votre liste pourrait apporter 25–95 des 255 billets visés."));
        getPlan(e, "en").andExpect(jsonPath("$.summary.locale").value("en"));
        assertThat(jdbc.queryForObject("select count(*) from audience_plans where event_id = ?", Integer.class, e.getId()))
                .isEqualTo(1);
        String summaries = (String) row(id).get("summaries");
        assertThat((Map<String, Object>) JsonPath.read(summaries, "$")).containsOnlyKeys("en", "fr");
        assertThat(row(id).get("tokens_in")).isEqualTo(2420);
    }

    @Test
    void configuredPrices_storeTheCost() throws Exception {
        flips.set(props, "summaryPriceInputUsdPerMtok", new BigDecimal("0.15"));
        flips.set(props, "summaryPriceOutputUsdPerMtok", new BigDecimal("0.60"));
        answer("undated-en.json");
        Event e = event();

        String id = id(getPlan(e, "en"));
        drain();

        // 1210 × 0.15 + 190 × 0.60 = 295.5 per million tokens → 0.0002955 → 0.0003 (4 decimals).
        assertThat((BigDecimal) row(id).get("cost_usd")).isEqualByComparingTo("0.0003");
    }

    @Test
    void post_neverAsksForASummary() throws Exception {
        answer("undated-en.json");
        Event e = event();

        mvc.perform(post(url(e)).with(auth(owner))).andExpect(status().isOk());
        drain();

        verify(chat, never()).prompt();
    }

    @Test
    void backgroundRefresh_neverAsksForASummary() {
        answer("undated-en.json");
        Event e = event();

        assertThat(planService.refresh(e.getId())).isEqualTo(PlanService.Refresh.CREATED);
        drain();

        verify(chat, never()).prompt();
    }

    @Test
    void switchedOff_getNeverAsks() throws Exception {
        flips.set(props, "summaryEnabled", false);
        answer("undated-en.json");
        Event e = event();

        getPlan(e, "en").andExpect(jsonPath("$.summary").value(nullValue()));
        drain();

        verify(chat, never()).prompt();
    }

    @Test
    void momentumTrigger_refreshesThePlan_butNeverAsksForASummary() {
        answer("undated-en.json");
        Event e = event();

        events.publishEvent(new MomentumTriggered(orgId, e.getId(), "slump"));
        drain(refreshExecutor);
        drain();

        assertThat(jdbc.queryForObject("select count(*) from audience_plans where event_id = ?", Integer.class,
                e.getId())).isEqualTo(1);
        verify(chat, never()).prompt();
    }

    @Test
    void newPlanWithinTheCooldown_copiesTheSummary_withoutACall() throws Exception {
        answer("undated-en.json");
        Event e = event();
        String first = id(getPlan(e, "en"));
        drain();
        AudiencePlanResponse.Summary stored = planService.current(orgId, e.getId(), "en").summary();

        String second = id(mvc.perform(post(url(e)).with(auth(owner))).andExpect(status().isOk()));
        getPlan(e, "en").andExpect(jsonPath("$.id").value(second)).andExpect(jsonPath("$.summary").value(nullValue()));
        drain();

        assertThat(second).isNotEqualTo(first);
        assertThat(planService.current(orgId, e.getId(), "en").summary()).isEqualTo(stored);
        getPlan(e, "en").andExpect(jsonPath("$.summary.aiDisclosure").value("mode=ai-originated"))
                .andExpect(jsonPath("$.versions.model").value("openai/gpt-4o-mini-2024-07-18"));
        Map<String, Object> row = row(second);
        assertThat(row.get("tokens_in")).isNull();
        assertThat(row.get("tokens_out")).isNull();
        verify(chat, times(1)).prompt();
    }

    @Test
    void newPlanWithOtherNumbers_withinTheCooldown_getsTheTemplate_withoutACall() throws Exception {
        answer("undated-en.json");
        Event e = event();
        getPlan(e, "en");
        drain();

        String second = id(mvc.perform(post(url(e)).with(auth(owner))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"targetPct\":50}"))
                .andExpect(status().isOk()));
        getPlan(e, "en");
        drain();

        getPlan(e, "en").andExpect(jsonPath("$.id").value(second))
                .andExpect(jsonPath("$.summary.aiGenerated").value(false))
                .andExpect(jsonPath("$.summary.aiDisclosure").value(nullValue()));
        verify(chat, times(1)).prompt();
    }

    @Test
    void store_keepsAnExistingLocale_butCountsTheTwinsSpend() throws Exception {
        answer("undated-en.json");
        Event e = event();
        String id = id(getPlan(e, "en"));
        drain();
        AudiencePlanResponse.Summary first = planService.current(orgId, e.getId(), "en").summary();

        AudiencePlanResponse.Summary twin = SummaryTemplates.summary(SummaryFixtures.warm(), "en", Instant.now());
        summarizer.store(UUID.fromString(id), "en", new Summarizer.Generated(twin, new Summarizer.Spend(10, 5, 1)));

        assertThat(planService.current(orgId, e.getId(), "en").summary()).isEqualTo(first);
        assertThat(row(id).get("tokens_in")).isEqualTo(1220);
        assertThat(row(id).get("tokens_out")).isEqualTo(195);
    }

    @Test
    void store_templateWithoutCalls_leavesTokensAndModelUnset() throws Exception {
        Event e = event();
        flips.set(props, "summaryEnabled", false);
        String id = id(getPlan(e, "uk"));

        AudiencePlanResponse.Summary template = SummaryTemplates.summary(SummaryFixtures.warm(), "uk", Instant.now());
        summarizer.store(UUID.fromString(id), "uk", new Summarizer.Generated(template, Summarizer.Spend.NONE));

        getPlan(e, "uk").andExpect(jsonPath("$.summary.aiGenerated").value(false))
                .andExpect(jsonPath("$.summary.aiDisclosure").value(nullValue()))
                .andExpect(jsonPath("$.versions.model").value(nullValue()));
        Map<String, Object> row = row(id);
        assertThat(row.get("tokens_in")).isNull();
        assertThat(row.get("tokens_out")).isNull();
        assertThat(row.get("model_id")).isNull();
    }

    @Test
    void store_skipsADeletedPlan() {
        summarizer.store(UUID.randomUUID(), "en", new Summarizer.Generated(
                SummaryTemplates.summary(SummaryFixtures.warm(), "en", Instant.now()), Summarizer.Spend.NONE));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Each prompt answers with the next recorded content (the last one repeats). */
    private void answer(String... recordings) {
        List<ChatResponse> responses = new ArrayList<>();
        for (String r : recordings) {
            String recorded = SummaryFixtures.recorded(r);
            String content = JsonPath.read(recorded, "$.choices[0].message.content");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("refusal", "");
            Generation g = new Generation(AssistantMessage.builder().content(content).properties(props).build(),
                    ChatGenerationMetadata.builder().finishReason("STOP").build());
            ChatResponseMetadata meta = ChatResponseMetadata.builder()
                    .model(JsonPath.read(recorded, "$.model"))
                    .usage(new DefaultUsage(JsonPath.read(recorded, "$.usage.prompt_tokens"),
                            JsonPath.read(recorded, "$.usage.completion_tokens")))
                    .build();
            responses.add(new ChatResponse(List.of(g), meta));
        }
        var stub = when(call.chatResponse());
        for (ChatResponse r : responses) stub = stub.thenReturn(r);
    }

    /** Waits until every summary task submitted so far has run (the executor has one thread, FIFO). */
    private void drain() {
        drain(summaryExecutor);
    }

    private static void drain(Executor executor) {
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(done::countDown);
        try {
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> row(String planId) {
        return jdbc.queryForMap("select summaries, tokens_in, tokens_out, cost_usd, model_id from audience_plans where id = ?",
                UUID.fromString(planId));
    }

    private Event event() {
        User u = new User();
        u.setEmail("summary-owner-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        u = userRepo.save(u);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Summary Night");
        e.setSlug("summary-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setGenre("House & Techno");
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minusSeconds(3600));
        e.setCreatedBy(u.getId());
        e.setCurrency("EUR");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(LocalDate.now(PARIS).plusDays(28).atTime(20, 0).atZone(PARIS).toInstant());
        e = eventRepo.save(e);
        TicketTier t = new TicketTier();
        t.setEventId(e.getId());
        t.setName("GA");
        t.setPriceMinor(2000);
        t.setQuantity(300);
        t.setEnabled(true);
        tierRepo.save(t);

        // The checked warm fixture as real members: 40 loyal, 70 repeat, 235 first-timers, 12 legacy-unproven.
        PlanPopulation.mailable(jdbc, logic, clock, orgId, "loyal", Map.of(HOUSE, 1.0), 40);
        PlanPopulation.mailable(jdbc, logic, clock, orgId, "repeat", Map.of(HOUSE, 1.0), 70);
        PlanPopulation.mailable(jdbc, logic, clock, orgId, "first_timer", Map.of(HOUSE, 1.0), 235);
        PlanPopulation.legacyUnproven(jdbc, clock, orgId, 12);
        return e;
    }

    private ResultActions getPlan(Event e, String locale) throws Exception {
        var req = get(url(e)).with(auth(owner));
        if (locale != null) req = req.param("locale", locale);
        return mvc.perform(req).andExpect(status().isOk());
    }

    private static String url(Event e) {
        return "/api/v1/events/" + e.getId() + "/audience-plan";
    }

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
