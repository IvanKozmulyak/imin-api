package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.PortraitCatchment;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.PortraitTown;
import com.imin.iminapi.audienceplan.service.PortraitLlmClient.Reply;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.Pair;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.Row;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.Spend;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.StoredGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Research runs over recorded OpenRouter bodies ({@link PortraitLlmClient#parse}); no model is called. */
class PortraitResearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    private static final String HOUSE = "house & techno";
    private static final Pair METZ = new Pair(HOUSE, "metz");
    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String EXTRACT_OK = """
            {"model":"anthropic/claude-haiku-4.5","choices":[{"finish_reason":"stop","message":{"content":
             "{\\"groups\\":[{\\"label\\":\\"Techno regulars of Metz\\",\\"description\\":\\"They follow the BAM nights.\\",\\"basis\\":\\"regulars\\",\\"towns\\":[\\"Metz\\"],\\"sourceUrls\\":[\\"https://www.bam-metz.fr/programme\\"]},{\\"label\\":\\"Nancy students\\",\\"basis\\":\\"students\\",\\"towns\\":[\\"Nancy\\"],\\"sourceUrls\\":[\\"https://made-up.example/x\\"]}]}"}}],
             "usage":{"prompt_tokens":800,"completion_tokens":200,"cost":0.004}}""";
    private static final String PAUSED = """
            {"choices":[{"finish_reason":"stop","native_finish_reason":"pause_turn","message":{"content":"Searching..."}}],
             "usage":{"prompt_tokens":900,"completion_tokens":20}}""";
    private static final String REFUSED = """
            {"choices":[{"finish_reason":"stop","message":{"content":"","refusal":"I can't help with that."}}],
             "usage":{"prompt_tokens":900,"completion_tokens":10}}""";
    private static final String EMPTY_JSON = """
            {"choices":[{"finish_reason":"stop","message":{"content":"{\\"groups\\":[]}"}}],
             "usage":{"prompt_tokens":800,"completion_tokens":5}}""";
    private static final String IDENTITY_ONLY = """
            {"choices":[{"finish_reason":"stop","message":{"content":
             "{\\"groups\\":[{\\"label\\":\\"Muslim students of Metz\\",\\"basis\\":\\"students\\",\\"sourceUrls\\":[]}]}"}}],
             "usage":{"prompt_tokens":800,"completion_tokens":50}}""";

    private final PortraitLlmClient llm = mock(PortraitLlmClient.class);
    private final LlmPayloadGuard guard = spy(new LlmPayloadGuard());
    private final PortraitResearchStore store = mock(PortraitResearchStore.class);
    private final PortraitService portraits = mock(PortraitService.class);
    private final AudiencePlanProperties props = new AudiencePlanProperties();
    private Executor executor = Runnable::run;

    @BeforeEach
    void setUp() {
        when(portraits.openData(HOUSE, "metz")).thenReturn(metzOpenData());
        when(llm.research(anyString(), anyString(), anyString(), any())).thenReturn(recorded(PortraitLlmClientTest.RESEARCH_OK));
        when(llm.extract(anyString(), anyString(), anyString(), any())).thenReturn(recorded(EXTRACT_OK));
    }

    private PortraitResearchService service() {
        return new PortraitResearchService(llm, guard, new IdentityLabelGuard(List.of()), store, portraits, props,
                executor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Reply recorded(String body) {
        return PortraitLlmClient.parse(body);
    }

    private static AudiencePortraitResponse metzOpenData() {
        List<PortraitTown> towns = List.of(new PortraitTown("metz", "Metz", "FR", 0, true),
                new PortraitTown("nancy", "Nancy", "FR", 48, true),
                new PortraitTown("luxembourg", "Luxembourg", "LU", 55, false));
        return new AudiencePortraitResponse(HOUSE, "metz", new PortraitCatchment(70, "fr_catchment", towns), List.of(),
                null, new AudiencePortraitResponse.Versions(1));
    }

    @SuppressWarnings("unchecked")
    private List<StoredGroup> savedGroups() {
        ArgumentCaptor<List<StoredGroup>> groups = ArgumentCaptor.forClass(List.class);
        verify(store).saveReady(eq(METZ), groups.capture(), eq(NOW), eq(NOW.plus(Duration.ofDays(90))), any());
        return groups.getValue();
    }

    // ── one run ─────────────────────────────────────────────────────────────

    @Test
    void usableAnswers_storeVettedGroups_withTheSpendAsReported() {
        assertThat(service().research(ORG, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.READY);

        List<StoredGroup> groups = savedGroups();
        assertThat(groups).extracting(StoredGroup::label).containsExactly("Techno regulars of Metz", "Nancy students");
        assertThat(groups.get(0).confidence()).isEqualTo("cited");
        assertThat(groups.get(0).towns()).containsExactly("metz");
        assertThat(groups.get(1).confidence()).isEqualTo("assumed");
        assertThat(groups.get(1).sources()).isEmpty();
        ArgumentCaptor<Spend> spend = ArgumentCaptor.forClass(Spend.class);
        verify(store).saveReady(any(), anyList(), any(), any(), spend.capture());
        assertThat(spend.getValue()).isEqualTo(new Spend("anthropic/claude-haiku-4.5", 2000, 500,
                new BigDecimal("0.0361")));
        verify(store, never()).saveEmpty(any(), any(), any(), any());
    }

    @Test
    void theExtractionSeesThisRunsSources_andOnlyTheFrenchTowns() {
        service().research(ORG, HOUSE, "metz");

        verify(llm).extract(eq("anthropic/claude-haiku-4.5"), anyString(),
                contains("TOWNS: Metz, Nancy\nSOURCES:\n- https://www.bam-metz.fr/programme (BAM Metz)\n"
                        + "- https://trinitaires.fr/agenda/ (Les Trinitaires)\nNOTES:\nMetz has an active techno scene"), any());
        verify(llm).research(eq("anthropic/claude-haiku-4.5"), anyString(),
                eq("Genre: house & techno\nTown: Metz\nTowns nearby: Metz (FR, 0 km), Nancy (FR, 48 km), "
                        + "Luxembourg (LU, 55 km)"), any());
    }

    @Test
    void costNotReported_isPricedFromTheConfiguredRates() {
        when(llm.extract(anyString(), anyString(), anyString(), any())).thenReturn(recorded(EXTRACT_OK.replace(",\"cost\":0.004", "")));

        service().research(ORG, HOUSE, "metz");

        ArgumentCaptor<Spend> spend = ArgumentCaptor.forClass(Spend.class);
        verify(store).saveReady(any(), anyList(), any(), any(), spend.capture());
        // 2000 in × $1 + 500 out × $5 per million tokens
        assertThat(spend.getValue().costUsd()).isEqualByComparingTo(new BigDecimal("0.0045"));
    }

    @Test
    void guard_checksBothPrompts_withoutOrgNames() {
        service().research(ORG, HOUSE, "metz");

        verify(guard).check(contains("Genre: house & techno"), isNull());
        verify(guard).check(contains("NOTES:\nMetz has an active techno scene"), isNull());
        verify(guard, times(2)).check(anyString(), isNull());
    }

    @Test
    void guardRefusingTheResearchPrompt_makesNoCall_andStoresEmpty() {
        doThrow(new LlmPayloadGuard.Rejected(LlmPayloadGuard.Reason.EMAIL)).when(guard).check(anyString(), isNull());

        assertThat(service().research(ORG, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.EMPTY);

        verifyNoInteractions(llm);
        verify(store).saveEmpty(METZ, NOW, NOW.plus(Duration.ofDays(1)), new Spend("anthropic/claude-haiku-4.5", 0, 0, null));
    }

    @Test
    void guardRefusingTheExtractionPrompt_skipsTheSecondCall() {
        // A phone number from a venue page reached the notes.
        when(llm.research(anyString(), anyString(), anyString(), any())).thenReturn(recorded(
                PortraitLlmClientTest.RESEARCH_OK.replace("Les Trinitaires.\"", "Les Trinitaires, call 03 87 74 16 16.\"")));

        assertThat(service().research(ORG, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.EMPTY);

        verify(llm, never()).extract(anyString(), anyString(), anyString(), any());
        verify(store).saveEmpty(eq(METZ), eq(NOW), eq(NOW.plus(Duration.ofDays(1))), any());
        verify(store, never()).saveReady(any(), anyList(), any(), any(), any());
    }

    @Test
    void pausedResearchTurn_givesNoResearchGroups() {
        when(llm.research(anyString(), anyString(), anyString(), any())).thenReturn(recorded(PAUSED));

        assertThat(service().research(ORG, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.EMPTY);

        verify(llm, never()).extract(anyString(), anyString(), anyString(), any());
        ArgumentCaptor<Spend> spend = ArgumentCaptor.forClass(Spend.class);
        verify(store).saveEmpty(eq(METZ), eq(NOW), eq(NOW.plus(Duration.ofDays(1))), spend.capture());
        assertThat(spend.getValue().tokensIn()).isEqualTo(900);
        verify(store, never()).saveReady(any(), anyList(), any(), any(), any());
    }

    @Test
    void refusedResearch_givesNoResearchGroups() {
        when(llm.research(anyString(), anyString(), anyString(), any())).thenReturn(recorded(REFUSED));

        assertThat(service().research(ORG, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.EMPTY);

        verify(llm, never()).extract(anyString(), anyString(), anyString(), any());
        verify(store, never()).saveReady(any(), anyList(), any(), any(), any());
    }

    @Test
    void refusedEmptyOrAllIdentityExtraction_givesNoResearchGroups() {
        for (String body : List.of(REFUSED, EMPTY_JSON, IDENTITY_ONLY)) {
            PortraitResearchStore fresh = mock(PortraitResearchStore.class);
            when(llm.extract(anyString(), anyString(), anyString(), any())).thenReturn(recorded(body));
            PortraitResearchService s = new PortraitResearchService(llm, guard, new IdentityLabelGuard(List.of()),
                    fresh, portraits, props, executor, Clock.fixed(NOW, ZoneOffset.UTC));

            assertThat(s.research(ORG, HOUSE, "metz")).as(body).isEqualTo(PortraitResearchService.Outcome.EMPTY);

            verify(fresh).saveEmpty(eq(METZ), eq(NOW), eq(NOW.plus(Duration.ofDays(1))), any());
            verify(fresh, never()).saveReady(any(), anyList(), any(), any(), any());
        }
    }

    @Test
    void llmException_storesEmpty() {
        when(llm.research(anyString(), anyString(), anyString(), any())).thenThrow(new IllegalStateException("read timed out"));

        assertThat(service().research(ORG, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.EMPTY);

        verify(store).saveEmpty(METZ, NOW, NOW.plus(Duration.ofDays(1)), new Spend("anthropic/claude-haiku-4.5", 0, 0, null));
    }

    @Test
    void theConfiguredModel_isUsedForBothCalls() {
        props.setPortraitModel("openai/gpt-4o-mini:online");

        service().research(ORG, HOUSE, "metz");

        verify(llm).research(eq("openai/gpt-4o-mini:online"), anyString(), anyString(), any());
        verify(llm).extract(eq("openai/gpt-4o-mini:online"), anyString(), anyString(), any());
    }

    // ── caps ───────────────────────────────────────────────────────────────

    @Test
    void perOrgCap_reached_skipsWithoutACallOrAWrite_otherOrgsGoOn() {
        props.setPortraitDailyCapPerOrg(2);
        PortraitResearchService s = service();

        assertThat(s.research(ORG, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.READY);
        assertThat(s.research(ORG, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.CAPPED);
        assertThat(s.research(OTHER, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.READY);

        verify(llm, times(2)).research(anyString(), anyString(), anyString(), any());
        verify(store, times(2)).saveReady(any(), anyList(), any(), any(), any());
        verify(store, never()).saveEmpty(any(), any(), any(), any());
    }

    @Test
    void globalCap_reached_skipsEveryone_includingTheRefreshJob() {
        props.setPortraitDailyCapGlobal(3);
        PortraitResearchService s = service();

        assertThat(s.research(null, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.READY);
        assertThat(s.research(null, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.CAPPED);
        assertThat(s.research(OTHER, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.CAPPED);

        verify(llm, times(1)).research(anyString(), anyString(), anyString(), any());
    }

    @Test
    void globalCapZero_neverCalls() {
        props.setPortraitDailyCapGlobal(0);

        assertThat(service().research(ORG, HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.CAPPED);

        verifyNoInteractions(llm);
        verify(store, never()).saveEmpty(any(), any(), any(), any());
    }

    @Test
    void capsResetOnTheNextUtcDay() {
        props.setPortraitDailyCapPerOrg(2);
        Instant[] now = {NOW};
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        PortraitResearchService s = new PortraitResearchService(llm, guard, new IdentityLabelGuard(List.of()), store,
                portraits, props, executor, moving);

        assertThat(s.takeCalls(ORG, 2)).isTrue();
        assertThat(s.takeCalls(ORG, 2)).isFalse();
        now[0] = Instant.parse("2026-10-02T00:00:00Z");
        assertThat(s.takeCalls(ORG, 2)).isTrue();
    }

    @Test
    void aServerErrorRetry_takesOneMoreCall_andIsRefusedWhenTheCapIsShort() {
        props.setPortraitDailyCapPerOrg(3);
        ArgumentCaptor<java.util.function.BooleanSupplier> retry =
                ArgumentCaptor.forClass(java.util.function.BooleanSupplier.class);
        PortraitResearchService s = service();

        s.research(ORG, HOUSE, "metz");
        verify(llm).research(anyString(), anyString(), anyString(), retry.capture());

        assertThat(retry.getValue().getAsBoolean()).isTrue();
        assertThat(retry.getValue().getAsBoolean()).isFalse();
        assertThat(s.takeCalls(ORG, 1)).isFalse();
    }

    @Test
    void unknownCity_isNeitherRecordedNorResearched() {
        when(portraits.openData(HOUSE, "atlantis")).thenReturn(new AudiencePortraitResponse(HOUSE, "atlantis", null,
                List.of(), null, new AudiencePortraitResponse.Versions(1)));
        PortraitResearchService s = service();

        s.requestIfMissing(ORG, HOUSE, "atlantis");
        assertThat(s.research(ORG, HOUSE, "atlantis")).isEqualTo(PortraitResearchService.Outcome.SKIPPED);

        verifyNoInteractions(llm, store);
    }

    @Test
    void catchmentWithoutFrenchTowns_isResearched_withNoTownsToName() {
        AudiencePortraitResponse lux = new AudiencePortraitResponse(HOUSE, "luxembourg",
                new PortraitCatchment(20, "fr_catchment", List.of(new PortraitTown("luxembourg", "Luxembourg", "LU", 0,
                        false))), List.of(), null, new AudiencePortraitResponse.Versions(1));
        when(portraits.openData(HOUSE, "luxembourg")).thenReturn(lux);

        service().research(ORG, HOUSE, "luxembourg");

        verify(llm).research(anyString(), anyString(), eq("Genre: house & techno\nTown: Luxembourg\nTowns nearby: "
                + "Luxembourg (LU, 0 km)"), any());
        verify(llm).extract(anyString(), anyString(), contains("TOWNS: (none)\n"), any());
    }

    @Test
    void refresh_whileAGetRunIsInFlight_isBusy_andWritesNothing() {
        when(store.touch(HOUSE, "metz", NOW)).thenReturn(row("pending", null));
        List<Runnable> queued = new java.util.ArrayList<>();
        executor = queued::add;
        PortraitResearchService s = service();
        s.requestIfMissing(ORG, HOUSE, "metz");

        assertThat(s.refresh(HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.BUSY);
        verifyNoInteractions(llm);

        queued.get(0).run();
        assertThat(s.refresh(HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.READY);
    }

    @Test
    void aGet_whileTheRefreshRuns_isNotScheduled() {
        when(store.touch(HOUSE, "metz", NOW)).thenReturn(row("pending", null));
        List<Runnable> queued = new java.util.ArrayList<>();
        executor = queued::add;
        PortraitResearchService[] s = new PortraitResearchService[1];
        when(llm.research(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            s[0].requestIfMissing(ORG, HOUSE, "metz");
            return recorded(PortraitLlmClientTest.RESEARCH_OK);
        });
        s[0] = service();

        assertThat(s[0].refresh(HOUSE, "metz")).isEqualTo(PortraitResearchService.Outcome.READY);
        assertThat(queued).isEmpty();
    }

    // ── lazy request ───────────────────────────────────────────────────────

    private Row row(String status, Instant expiresAt) {
        return new Row(HOUSE, "metz", status, List.of(), 1, null, expiresAt, NOW, null);
    }

    @Test
    void request_pendingPair_isResearched() {
        when(store.touch(HOUSE, "metz", NOW)).thenReturn(row("pending", null));

        service().requestIfMissing(ORG, HOUSE, "metz");

        verify(llm).research(anyString(), anyString(), anyString(), any());
        verify(store).saveReady(eq(METZ), anyList(), any(), any(), any());
    }

    @Test
    void request_readyPair_onlyTouches_evenWhenExpired() {
        when(store.touch(HOUSE, "metz", NOW)).thenReturn(row("ready", NOW.minus(Duration.ofDays(1))));

        service().requestIfMissing(ORG, HOUSE, "metz");

        verify(store).touch(HOUSE, "metz", NOW);
        verifyNoInteractions(llm);
    }

    @Test
    void request_emptyPair_retriesOnlyAfterItsRetryTime() {
        when(store.touch(HOUSE, "metz", NOW)).thenReturn(row("empty", NOW.plusSeconds(60)));
        service().requestIfMissing(ORG, HOUSE, "metz");
        verifyNoInteractions(llm);

        when(store.touch(HOUSE, "metz", NOW)).thenReturn(row("empty", NOW));
        service().requestIfMissing(ORG, HOUSE, "metz");
        verify(llm).research(anyString(), anyString(), anyString(), any());
    }

    @Test
    void request_neverThrows_whenTheStoreFails() {
        when(store.touch(HOUSE, "metz", NOW)).thenThrow(new IllegalStateException("db down"));

        service().requestIfMissing(ORG, HOUSE, "metz");

        verifyNoInteractions(llm);
    }

    @Test
    void request_fullQueue_isForgotten_soTheNextGetAsksAgain() {
        when(store.touch(HOUSE, "metz", NOW)).thenReturn(row("pending", null));
        executor = r -> {
            throw new RejectedExecutionException("full");
        };
        PortraitResearchService s = service();

        s.requestIfMissing(ORG, HOUSE, "metz");
        s.requestIfMissing(ORG, HOUSE, "metz");

        verify(store, times(2)).touch(HOUSE, "metz", NOW);
        verifyNoInteractions(llm);
    }

    @Test
    void request_whileOneIsInFlight_isNotScheduledTwice() {
        when(store.touch(HOUSE, "metz", NOW)).thenReturn(row("pending", null));
        List<Runnable> queued = new java.util.ArrayList<>();
        executor = queued::add;
        PortraitResearchService s = service();

        s.requestIfMissing(ORG, HOUSE, "metz");
        s.requestIfMissing(OTHER, HOUSE, "metz");
        assertThat(queued).hasSize(1);

        queued.get(0).run();
        s.requestIfMissing(OTHER, HOUSE, "metz");
        assertThat(queued).hasSize(2);
    }
}
