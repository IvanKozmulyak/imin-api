package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Summarizer.generate against recorded OpenRouter answers; no live call is ever made. */
class SummarizerTest {

    private static final Instant NOW = Instant.parse("2026-09-26T09:00:00Z");
    private static final UUID ORG = UUID.randomUUID();

    private MockRestServiceServer server;
    private final List<String> requestBodies = new ArrayList<>();
    private ChatClient chat;
    private LlmPayloadGuard guard;
    private JdbcTemplate jdbc;
    private AudiencePlanProperties props;

    @BeforeEach
    void setUp() {
        RestClient.Builder http = RestClient.builder().requestInterceptor((req, body, exec) -> {
            requestBodies.add(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            return exec.execute(req, body);
        });
        server = MockRestServiceServer.bindTo(http).build();
        chat = SummaryFixtures.replaying(http);
        guard = spy(new LlmPayloadGuard());
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any(), any()))
                .thenReturn(List.of("Camille Durand", "Mo"));
        props = new AudiencePlanProperties();
    }

    private Summarizer summarizer(ChatClient client) {
        return new Summarizer(client, guard, props, jdbc, null, Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Summarizer.Generated generate(String locale) {
        return summarizer(chat).generate(ORG, SummaryFixtures.warm(), locale);
    }

    // ── valid ────────────────────────────────────────────────────────────────

    @Test
    void validAnswer_isUsedAsAiText_withMarkerModelAndTokens() {
        SummaryFixtures.expectAnswer(server, "valid-en.json");

        Summarizer.Generated g = generate("en");

        server.verify();
        AudiencePlanResponse.Summary s = g.summary();
        assertThat(s.headline()).isEqualTo("Your list could bring 25–95 of the 255 tickets you are aiming for.");
        assertThat(s.segmentLines()).hasSize(3);
        assertThat(s.segmentLines().get(2)).isEqualTo("First-timers: 235 can be emailed, 10–45 tickets expected.");
        assertThat(s.gapLine()).contains("160–230");
        assertThat(s.actions()).hasSize(3);
        assertThat(s.assumptions()).containsExactly("The target is 85% of 300 tickets.", "Each order is counted as 1.6 tickets.");
        assertThat(s.locale()).isEqualTo("en");
        assertThat(s.aiGenerated()).isTrue();
        assertThat(s.aiDisclosure()).isEqualTo("mode=ai-originated");
        assertThat(s.model()).isEqualTo("openai/gpt-4o-mini-2024-07-18");
        assertThat(s.generatedAt()).isEqualTo(NOW);
        assertThat(g.spend()).isEqualTo(new Summarizer.Spend(1210, 190, 1));
    }

    @Test
    void request_carriesSummaryModelLowTemperatureAndData() {
        props.setSummaryModel("anthropic/claude-haiku-test");
        SummaryFixtures.expectAnswer(server, "valid-en.json");

        generate("en");

        String body = requestBodies.get(0);
        assertThat((String) JsonPath.read(body, "$.model")).isEqualTo("anthropic/claude-haiku-test");
        assertThat((Double) JsonPath.read(body, "$.temperature")).isEqualTo(0.2);
        String system = JsonPath.read(body, "$.messages[0].content");
        assertThat(system).contains("Write in English.").contains("exactly 3 strings, one per entry of DATA.segments");
        String user = JsonPath.read(body, "$.messages[1].content");
        assertThat(user).startsWith("DATA:\n").doesNotContain("previous answer");
    }

    @Test
    void blankSummaryModel_usesHaiku45() {
        props.setSummaryModel(" ");
        SummaryFixtures.expectAnswer(server, "valid-en.json");

        generate("en");

        assertThat((String) JsonPath.read(requestBodies.get(0), "$.model")).isEqualTo("anthropic/claude-haiku-4.5");
    }

    // ── numeric check ────────────────────────────────────────────────────────

    @Test
    void inventedNumber_retriesOnce_thenUsesTheValidAnswer() {
        SummaryFixtures.expectAnswer(server, "invented-en.json");
        SummaryFixtures.expectAnswer(server, "valid-en.json");

        Summarizer.Generated g = generate("en");

        server.verify();
        assertThat(g.summary().aiGenerated()).isTrue();
        assertThat(g.summary().headline()).doesNotContain("52");
        assertThat((String) JsonPath.read(requestBodies.get(1), "$.messages[1].content"))
                .startsWith("Your previous answer used a number that is not in DATA.");
        assertThat(g.spend()).isEqualTo(new Summarizer.Spend(2420, 380, 2));
    }

    @Test
    void inventedTwice_fallsBackToTheTemplate_andCountsBothCalls() {
        SummaryFixtures.expectAnswer(server, "invented-en.json");
        SummaryFixtures.expectAnswer(server, "invented-en.json");

        Summarizer.Generated g = generate("en");

        server.verify();
        assertTemplate(g.summary(), "en");
        assertThat(g.spend()).isEqualTo(new Summarizer.Spend(2420, 380, 2));
    }

    // ── refusal / empty / malformed / exception ─────────────────────────────

    @Test
    void refusal_fallsBackToTheTemplate_withoutRetry() {
        SummaryFixtures.expectAnswer(server, "refusal.json");

        Summarizer.Generated g = generate("en");

        server.verify();
        assertTemplate(g.summary(), "en");
        assertThat(g.spend().calls()).isEqualTo(1);
    }

    @Test
    void emptyAnswer_fallsBackToTheTemplate() {
        SummaryFixtures.expectAnswer(server, "empty.json");

        assertTemplate(generate("en").summary(), "en");
        server.verify();
    }

    @Test
    void contentFilter_fallsBackToTheTemplate() {
        SummaryFixtures.expectAnswer(server, "content-filter.json");

        assertTemplate(generate("en").summary(), "en");
        server.verify();
    }

    @Test
    void wrongNumberOfSegmentLines_fallsBackToTheTemplate() {
        SummaryFixtures.expectAnswer(server, "wrong-shape-en.json");

        assertTemplate(generate("en").summary(), "en");
        server.verify();
    }

    @Test
    void exception_fallsBackToTheTemplate_withNothingSpent() {
        ChatClient failing = mock(ChatClient.class);
        when(failing.prompt()).thenThrow(new IllegalStateException("upstream 503"));

        Summarizer.Generated g = summarizer(failing).generate(ORG, SummaryFixtures.warm(), "en");

        assertTemplate(g.summary(), "en");
        assertThat(g.spend()).isEqualTo(Summarizer.Spend.NONE);
    }

    @Test
    void lineOver400Chars_fallsBackToTheTemplate() {
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(withContent(validContent().replace("You still need 160–230 tickets",
                        "You still need 160–230 tickets" + " very".repeat(80))));

        Summarizer.Generated g = generate("en");

        server.verify();
        assertTemplate(g.summary(), "en");
        assertThat(g.spend().calls()).isEqualTo(1);
    }

    @Test
    void lineOfExactly400Chars_isKept() {
        String gap = "You still need 160–230 tickets.";
        String padded = gap + "x".repeat(Summarizer.MAX_LINE_CHARS - gap.length());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(withContent(validContent().replace(
                        "You still need 160–230 tickets from people who are not on your list.", padded)));

        assertThat(generate("en").summary().gapLine()).hasSize(Summarizer.MAX_LINE_CHARS);
    }

    @Test
    void malformedJson_fallsBackToTheTemplate_withoutRetry() {
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(withContent("{\"headline\": \"Your list could bring 25–95\", \"segmentLines\": [}"));

        Summarizer.Generated g = generate("en");

        server.verify();
        assertTemplate(g.summary(), "en");
        assertThat(g.spend().calls()).isEqualTo(1);
    }

    // ── daily cap ────────────────────────────────────────────────────────────

    @Test
    void dailyCap_reached_usesTheTemplate_withoutACall() {
        props.setSummaryDailyCapPerOrg(1);
        SummaryFixtures.expectAnswer(server, "valid-en.json");
        Summarizer s = summarizer(chat);
        assertThat(s.generate(ORG, SummaryFixtures.warm(), "en").summary().aiGenerated()).isTrue();

        Summarizer.Generated capped = s.generate(ORG, SummaryFixtures.warm(), "fr");

        server.verify();
        assertThat(requestBodies).hasSize(1);
        assertTemplate(capped.summary(), "fr");
        assertThat(capped.spend()).isEqualTo(Summarizer.Spend.NONE);
    }

    @Test
    void dailyCap_countsTheRetry() {
        props.setSummaryDailyCapPerOrg(1);
        SummaryFixtures.expectAnswer(server, "invented-en.json");

        Summarizer.Generated g = generate("en");

        server.verify();
        assertThat(requestBodies).hasSize(1);
        assertTemplate(g.summary(), "en");
        assertThat(g.spend()).isEqualTo(new Summarizer.Spend(1210, 190, 1));
    }

    @Test
    void dailyCap_isPerOrg_andResetsTheNextUtcDay() {
        props.setSummaryDailyCapPerOrg(1);
        Instant[] now = {Instant.parse("2026-09-26T23:59:00Z")};
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        Summarizer s = new Summarizer(chat, guard, props, jdbc, null, Runnable::run, moving);
        UUID other = UUID.randomUUID();

        assertThat(s.takeCall(ORG)).isTrue();
        assertThat(s.takeCall(ORG)).isFalse();
        assertThat(s.takeCall(other)).isTrue();
        now[0] = Instant.parse("2026-09-27T00:00:01Z");
        assertThat(s.takeCall(ORG)).isTrue();
    }

    // ── cooldown ─────────────────────────────────────────────────────────────

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** An earlier plan row of the event holding {@code summary} under its locale. */
    private void earlierRow(AudiencePlanResponse.Summary summary) throws Exception {
        String json = JSON.writeValueAsString(java.util.Map.of(summary.locale(), summary));
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("SELECT summaries FROM audience_plans"),
                eq(String.class), any(), any(), any(), any())).thenReturn(List.of(json));
    }

    private AudiencePlanResponse.Summary aiSummary(Instant generatedAt) {
        SummaryFixtures.expectAnswer(server, "valid-en.json");
        AudiencePlanResponse.Summary s = generate("en").summary();
        server.reset();
        requestBodies.clear();
        return new AudiencePlanResponse.Summary(s.headline(), s.segmentLines(), s.gapLine(), s.actions(),
                s.assumptions(), s.locale(), true, s.aiDisclosure(), s.model(), generatedAt);
    }

    @Test
    void cooldown_copiesARecentModelSummary_withItsMarker_andNoCall() throws Exception {
        AudiencePlanResponse.Summary earlier = aiSummary(NOW.minus(java.time.Duration.ofHours(23)));
        earlierRow(earlier);
        AudiencePlanResponse plan = SummaryFixtures.warm();

        Summarizer.Generated g = summarizer(chat).produce(ORG, plan, "en");

        assertThat(requestBodies).isEmpty();
        assertThat(g.summary()).isEqualTo(earlier);
        assertThat(g.summary().aiDisclosure()).isEqualTo("mode=ai-originated");
        assertThat(g.spend()).isEqualTo(Summarizer.Spend.NONE);
        verify(jdbc).queryForList(anyString(), eq(String.class), eq(ORG), eq(plan.eventId()), eq(plan.id()),
                eq(Summarizer.COOLDOWN_PLANS));
    }

    @Test
    void cooldown_over_asksTheModelAgain() throws Exception {
        earlierRow(aiSummary(NOW.minus(Summarizer.COOLDOWN)));
        SummaryFixtures.expectAnswer(server, "valid-en.json");

        Summarizer.Generated g = summarizer(chat).produce(ORG, SummaryFixtures.warm(), "en");

        server.verify();
        assertThat(g.summary().generatedAt()).isEqualTo(NOW);
        assertThat(g.spend().calls()).isEqualTo(1);
    }

    @Test
    void cooldown_ignoresARecentTemplate() throws Exception {
        earlierRow(SummaryTemplates.summary(SummaryFixtures.warm(), "en", NOW.minusSeconds(60)));
        SummaryFixtures.expectAnswer(server, "valid-en.json");

        Summarizer.Generated g = summarizer(chat).produce(ORG, SummaryFixtures.warm(), "en");

        server.verify();
        assertThat(g.summary().aiGenerated()).isTrue();
        assertThat(g.spend().calls()).isEqualTo(1);
    }

    @Test
    void cooldown_recentSummaryWithOtherNumbers_usesTheTemplate_withoutACall() throws Exception {
        earlierRow(aiSummary(NOW.minus(java.time.Duration.ofHours(1))));
        AudiencePlanResponse moved = SummaryFixtures.withGap(SummaryFixtures.warm(), 170, 240, List.of());

        Summarizer.Generated g = summarizer(chat).produce(ORG, moved, "en");

        assertThat(requestBodies).isEmpty();
        assertThat(g.summary().aiGenerated()).isFalse();
        assertThat(g.summary().gapLine()).isEqualTo(SummaryTemplates.summary(moved, "en", NOW).gapLine());
        assertThat(g.spend()).isEqualTo(Summarizer.Spend.NONE);
    }

    @Test
    void cooldown_unreadableEarlierRow_isSkipped() {
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("SELECT summaries FROM audience_plans"),
                eq(String.class), any(), any(), any(), any())).thenReturn(List.of("not json"));

        assertThat(summarizer(chat).recent(ORG, SummaryFixtures.warm(), "en")).isNull();
    }

    // ── locale ───────────────────────────────────────────────────────────────

    @Test
    void locale_isHonoured_inThePromptAndTheResult() {
        SummaryFixtures.expectAnswer(server, "valid-fr.json");

        Summarizer.Generated g = generate("fr");

        assertThat((String) JsonPath.read(requestBodies.get(0), "$.messages[0].content")).contains("Write in French.");
        assertThat(g.summary().locale()).isEqualTo("fr");
        assertThat(g.summary().aiGenerated()).isTrue();
        assertThat(g.summary().headline()).startsWith("Votre liste");
    }

    @Test
    void locale_fallback_usesThatLocalesTemplate() {
        SummaryFixtures.expectAnswer(server, "refusal.json");

        assertTemplate(generate("uk").summary(), "uk");
    }

    // ── payload guard ────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void guard_checksTheWholePrompt_withTheOrgsNames() {
        SummaryFixtures.expectAnswer(server, "valid-en.json");

        generate("en");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Collection<String>> names = ArgumentCaptor.forClass(Collection.class);
        verify(guard).check(prompt.capture(), names.capture());
        assertThat(prompt.getValue()).contains("Write in English.").contains("DATA:").contains("\"mailable\":345")
                .contains("Your previous answer");
        assertThat(names.getValue()).containsExactly("Camille Durand", "Mo");
        verify(jdbc).queryForList(anyString(), eq(String.class), eq(ORG), eq(ORG), eq(Summarizer.NAME_SAMPLE));
    }

    @Test
    void guardRejection_skipsTheLlm_andUsesTheTemplate() {
        ChatClient untouched = mock(ChatClient.class);
        doThrow(new LlmPayloadGuard.Rejected(LlmPayloadGuard.Reason.NAME)).when(guard).check(anyString(), any());

        Summarizer.Generated g = summarizer(untouched).generate(ORG, SummaryFixtures.warm(), "en");

        assertTemplate(g.summary(), "en");
        assertThat(g.spend()).isEqualTo(Summarizer.Spend.NONE);
        verifyNoInteractions(untouched);
    }

    // ── requestIfMissing ─────────────────────────────────────────────────────

    private Summarizer scheduling(java.util.concurrent.Executor executor) {
        ChatClient failing = mock(ChatClient.class);
        when(failing.prompt()).thenThrow(new IllegalStateException("no LLM in this test"));
        // A null TransactionTemplate makes store() throw, so a run task always ends in the failure path.
        return new Summarizer(failing, guard, props, jdbc, null, executor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void request_schedulesOnce_whileTheSameLocaleIsInFlight() {
        props.setSummaryEnabled(true);
        List<Runnable> queued = new ArrayList<>();
        Summarizer s = scheduling(queued::add);
        AudiencePlanResponse plan = SummaryFixtures.warm();

        s.requestIfMissing(ORG, plan, "FR");
        s.requestIfMissing(ORG, plan, "fr");
        s.requestIfMissing(ORG, plan, "en");

        assertThat(queued).hasSize(2);
    }

    @Test
    void request_afterAFailedTask_canScheduleAgain() {
        props.setSummaryEnabled(true);
        List<Runnable> queued = new ArrayList<>();
        Summarizer s = scheduling(queued::add);
        AudiencePlanResponse plan = SummaryFixtures.warm();

        s.requestIfMissing(ORG, plan, "en");
        queued.get(0).run();
        s.requestIfMissing(ORG, plan, "en");

        assertThat(queued).hasSize(2);
    }

    @Test
    void request_rejectedByAFullQueue_doesNotThrow_andIsForgotten() {
        props.setSummaryEnabled(true);
        List<Runnable> queued = new ArrayList<>();
        boolean[] full = {true};
        Summarizer s = scheduling(task -> {
            if (full[0]) throw new java.util.concurrent.RejectedExecutionException("full");
            queued.add(task);
        });
        AudiencePlanResponse plan = SummaryFixtures.warm();

        s.requestIfMissing(ORG, plan, "en");
        full[0] = false;
        s.requestIfMissing(ORG, plan, "en");

        assertThat(queued).hasSize(1);
    }

    @Test
    void request_skipsAStoredSummary() {
        props.setSummaryEnabled(true);
        List<Runnable> queued = new ArrayList<>();
        AudiencePlanResponse plan = SummaryFixtures.warm();
        AudiencePlanResponse withSummary = new AudiencePlanResponse(plan.id(), plan.eventId(), plan.mode(),
                plan.capacity(), plan.targetTickets(), plan.mailable(), plan.expected(), plan.coverage(), plan.gap(),
                plan.reachNeeded(), plan.gapExceedsTribe(), plan.segments(), plan.smallGroupsNotShown(),
                plan.otherGenreInvited(), plan.otherGenreHeldBack(), plan.exclusions(), plan.timing(), plan.newPeople(),
                plan.actions(), plan.assumptions(), SummaryTemplates.summary(plan, "en", NOW), plan.versions(),
                plan.createdAt());

        scheduling(queued::add).requestIfMissing(ORG, withSummary, "en");

        assertThat(queued).isEmpty();
    }

    @Test
    void request_switchedOff_schedulesNothing() {
        props.setSummaryEnabled(false);
        List<Runnable> queued = new ArrayList<>();

        scheduling(queued::add).requestIfMissing(ORG, SummaryFixtures.warm(), "en");

        assertThat(queued).isEmpty();
    }

    // ── data ─────────────────────────────────────────────────────────────────

    @Test
    void data_holdsNoIdsAndNoMiddleValues() {
        AudiencePlanResponse plan = SummaryFixtures.warm();

        String data = Summarizer.data(plan);

        assertThat(data).doesNotContain(plan.id().toString()).doesNotContain(plan.eventId().toString());
        // Mid values (52 total, 16/13/23 per segment, 25/12/6 % rates, 0.20 coverage) are left out.
        assertThat(SummaryNumbers.invented("52 16 13 23 25", SummaryNumbers.allowed(data)))
                .containsExactly("52", "16", "13", "23", "25");
        assertThat(data).doesNotContain("\"low\":0.1").doesNotContain("\"high\":0.36");
    }

    @Test
    void data_roundsPriorRangesOutwardTo5_asTheCardShowsThem() {
        String data = Summarizer.data(SummaryFixtures.warm());

        assertThat((Map<String, Object>) JsonPath.read(data, "$.expectedTickets")).containsExactly(
                Map.entry("low", 25), Map.entry("high", 95));
        assertThat((Map<String, Object>) JsonPath.read(data, "$.coverageOfTarget")).containsExactly(
                Map.entry("lowPct", 10), Map.entry("highPct", 40), Map.entry("verdict", "medium"));
        assertThat((Map<String, Object>) JsonPath.read(data, "$.gapTickets")).containsExactly(
                Map.entry("low", 160), Map.entry("high", 230));
        assertThat((List<Object>) JsonPath.read(data, "$.segments[*].responseRatePct")).containsExactly(
                Map.of("low", 10, "high", 40), Map.of("low", 5, "high", 20), Map.of("low", 0, "high", 15));
        assertThat((List<Object>) JsonPath.read(data, "$.segments[*].expectedTickets")).containsExactly(
                Map.of("low", 5, "high", 30), Map.of("low", 5, "high", 25), Map.of("low", 10, "high", 45));
    }

    @Test
    void data_keepsOwnAndIminRangesWhole_andPlanRangesFollowTheLeastSureSegment() {
        AudiencePlanResponse own = SummaryFixtures.withConfidence(SummaryFixtures.warm(), "own", "imin", "own");

        String data = Summarizer.data(own);

        assertThat((List<Object>) JsonPath.read(data, "$.segments[*].expectedTickets")).containsExactly(
                Map.of("low", 8, "high", 26), Map.of("low", 7, "high", 22), Map.of("low", 11, "high", 45));
        assertThat((List<Object>) JsonPath.read(data, "$.segments[*].responseRatePct")).containsExactly(
                Map.of("low", 12, "high", 40), Map.of("low", 6, "high", 20), Map.of("low", 3, "high", 12));
        // imin is the least sure level here: still whole, not rounded to 5.
        assertThat((Map<String, Object>) JsonPath.read(data, "$.expectedTickets")).containsExactly(
                Map.entry("low", 26), Map.entry("high", 93));
        assertThat((Map<String, Object>) JsonPath.read(data, "$.coverageOfTarget")).containsExactly(
                Map.entry("lowPct", 10), Map.entry("highPct", 36), Map.entry("verdict", "medium"));
        assertThat((Map<String, Object>) JsonPath.read(data, "$.gapTickets")).containsExactly(
                Map.entry("low", 162), Map.entry("high", 229));
    }

    @Test
    void data_newPeople_audienceGroupIsAPriorRange_contextGroupIsItsLowAlone() {
        AudiencePlanResponse plan = SummaryFixtures.withNewPeople(SummaryFixtures.warm(), List.of(
                SummaryFixtures.group("regulars", "audience", 1_234, 3_456),
                SummaryFixtures.group("students", "context", 18_000, 18_000),
                SummaryFixtures.group("genre_first", "audience", null, null)));

        String data = Summarizer.data(plan);

        assertThat((List<Object>) JsonPath.read(data, "$.newPeopleGroups[*].people")).containsExactly(
                Map.of("low", 1_230, "high", 3_460), 18_000, null);
    }

    @Test
    void answerQuotingTheUnroundedBounds_isRefused() {
        String raw = validContent().replace("25–95", "26–93");
        server.expect(requestTo(SummaryFixtures.BASE_URL + "/v1/chat/completions")).andRespond(withContent(raw));
        server.expect(requestTo(SummaryFixtures.BASE_URL + "/v1/chat/completions")).andRespond(withContent(raw));

        assertTemplate(generate("en").summary(), "en");
    }

    private static String validContent() {
        return JsonPath.read(SummaryFixtures.recorded("valid-en.json"), "$.choices[0].message.content");
    }

    /** A recorded envelope around {@code content}. */
    private static org.springframework.test.web.client.ResponseCreator withContent(String content) {
        String envelope = SummaryFixtures.recorded("valid-en.json");
        String body = JsonPath.parse(envelope).set("$.choices[0].message.content", content).jsonString();
        return withSuccess(body, org.springframework.http.MediaType.APPLICATION_JSON);
    }

    private static void assertTemplate(AudiencePlanResponse.Summary s, String locale) {
        AudiencePlanResponse.Summary expected = SummaryTemplates.summary(SummaryFixtures.warm(), locale, NOW);
        assertThat(s.aiGenerated()).isFalse();
        assertThat(s.aiDisclosure()).isNull();
        assertThat(s.model()).isNull();
        assertThat(s.locale()).isEqualTo(locale);
        assertThat(s.headline()).isEqualTo(expected.headline());
        assertThat(s.segmentLines()).isEqualTo(expected.segmentLines());
        assertThat(s.gapLine()).isEqualTo(expected.gapLine());
    }
}
