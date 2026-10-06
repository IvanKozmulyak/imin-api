package com.imin.iminapi.predictor.research;

import com.imin.iminapi.audienceplan.service.LlmPayloadGuard;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.research.ResearchLlmClient.Citation;
import com.imin.iminapi.predictor.research.ResearchLlmClient.Reply;
import com.imin.iminapi.predictor.research.ResearchLlmClient.Usage;
import com.imin.iminapi.predictor.rules.Finding;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBankLoader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebResearchServiceTest {

    private static final QuestionBank BANK = QuestionBankLoader.load(new DefaultResourceLoader());
    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate OCT17 = LocalDate.of(2026, 10, 17);
    private static final LocalDate DEC5 = LocalDate.of(2026, 12, 5);
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final String URL = "https://www.infoconcert.com/amelie-lens.html";
    private static final String QUOTE = "Amelie Lens au Rex Club le samedi 17 octobre 2026";
    private static final Usage USAGE = new Usage(4300, 250, 1, new BigDecimal("0.0125"));

    /** A clock tests can move. */
    static final class MutableClock extends Clock {
        Instant now = T0;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final ResearchLlmClient client = mock(ResearchLlmClient.class);
    private final WebResearchService service = new WebResearchService(client, new ResearchCache(clock),
            new LlmPayloadGuard(), BANK, new DateCheckProperties(), clock);

    private static Reply ok() {
        String json = """
                {"findings":[{"title":"Amelie Lens - Rex Club","type":"same_genre_event","url":"%s","quote":"%s",
                 "strength":2}]}""".formatted(URL, QUOTE);
        return new Reply(json, true, "stop",
                List.of(new Citation(URL, "Techno à Paris", "Agenda. " + QUOTE + ", 23h.")), USAGE);
    }

    private static WebResearchService.Request req(UUID org) {
        return new WebResearchService.Request(org, "Paris", "FR", "house & techno", "techno", List.of(OCT17, DEC5));
    }

    @Test
    void validatedFindingsAreAssignedPerNightWithTheCallTime() {
        when(client.research(anyString(), anyString(), anyString())).thenReturn(ok());

        WebResearchService.Outcome out = service.research(req(ORG_A));

        assertThat(out.done()).isTrue();
        assertThat(out.usage()).isEqualTo(USAGE);
        assertThat(out.model()).isEqualTo("anthropic/claude-haiku-4.5");
        assertThat(out.findings().get(OCT17)).singleElement().satisfies(f -> {
            assertThat(f.questionId()).isEqualTo("2.1");
            assertThat(f.fetchedAt()).isEqualTo(T0);
        });
        assertThat(out.findings().get(DEC5)).isEmpty();
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(client).research(org.mockito.ArgumentMatchers.eq("anthropic/claude-haiku-4.5"), anyString(),
                user.capture());
        assertThat(user.getValue()).contains("Paris, France", "house & techno (techno)", "2026-10-10", "2026-12-12");
    }

    @Test
    void cacheIsPerOrg() {
        when(client.research(anyString(), anyString(), anyString())).thenReturn(ok());

        service.research(req(ORG_A));
        WebResearchService.Outcome again = service.research(req(ORG_A));
        verify(client, times(1)).research(anyString(), anyString(), anyString());
        assertThat(again.done()).isTrue();
        assertThat(again.usage()).isNull();
        assertThat(again.findings().get(OCT17)).extracting(Finding::fetchedAt).containsExactly(T0);

        service.research(req(ORG_B));
        verify(client, times(2)).research(anyString(), anyString(), anyString());
    }

    @Test
    void cacheLastsUntilTheNextUtcDay() {
        when(client.research(anyString(), anyString(), anyString())).thenReturn(ok());
        service.research(req(ORG_A));
        clock.now = T0.plus(Duration.ofHours(13));   // 23:00 UTC, same day
        service.research(req(ORG_A));
        verify(client, times(1)).research(anyString(), anyString(), anyString());
        clock.now = T0.plus(Duration.ofHours(14));   // next UTC day
        service.research(req(ORG_A));
        verify(client, times(2)).research(anyString(), anyString(), anyString());
    }

    @Test
    void providerTimeoutGivesAFailedOutcome() {
        when(client.research(anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("Read timed out"));

        WebResearchService.Outcome out = service.research(req(ORG_A));

        assertThat(out.done()).isFalse();
        assertThat(out.reason()).isEqualTo("provider");
        assertThat(out.usage()).isNull();
        assertThat(out.findings()).isEmpty();
    }

    @Test
    void unusableEmptyOrMalformedAnswersFailAndKeepTheirUsage() {
        Reply ok = ok();
        when(client.research(anyString(), anyString(), anyString()))
                .thenReturn(new Reply(ok.text(), false, "length", ok.citations(), USAGE))
                .thenReturn(new Reply(ok.text(), true, "stop", List.of(), USAGE))
                .thenReturn(new Reply("no json here", true, "stop", ok.citations(), USAGE));

        for (String reason : List.of("unusable", "no_results", "parse")) {
            WebResearchService.Outcome out = service.research(req(ORG_A));
            assertThat(out.done()).as(reason).isFalse();
            assertThat(out.reason()).isEqualTo(reason);
            assertThat(out.usage()).isEqualTo(USAGE);
        }
    }

    @Test
    void emptyFindingsAreDoneWithoutCitations() {
        when(client.research(anyString(), anyString(), anyString()))
                .thenReturn(new Reply("{\"findings\":[]}", true, "stop", List.of(), USAGE));

        WebResearchService.Outcome out = service.research(req(ORG_A));

        assertThat(out.done()).isTrue();
        assertThat(out.reason()).isNull();
        assertThat(out.usage()).isEqualTo(USAGE);
        assertThat(out.findings()).containsOnlyKeys(OCT17, DEC5);
        assertThat(out.findings().values()).allSatisfy(fs -> assertThat(fs).isEmpty());
    }

    @Test
    void emptyFindingsWithCitationsAreDone() {
        when(client.research(anyString(), anyString(), anyString()))
                .thenReturn(new Reply("{\"findings\":[]}", true, "stop", ok().citations(), USAGE));

        WebResearchService.Outcome out = service.research(req(ORG_A));

        assertThat(out.done()).isTrue();
        assertThat(out.findings().values()).allSatisfy(fs -> assertThat(fs).isEmpty());
    }

    @Test
    void findingsWithoutCitationsFail() {
        when(client.research(anyString(), anyString(), anyString()))
                .thenReturn(new Reply(ok().text(), true, "stop", List.of(), USAGE));

        WebResearchService.Outcome out = service.research(req(ORG_A));

        assertThat(out.done()).isFalse();
        assertThat(out.reason()).isEqualTo("no_results");
        assertThat(out.usage()).isEqualTo(USAGE);
    }

    @Test
    void unparseableAnswerWithoutCitationsFailsAsParse() {
        when(client.research(anyString(), anyString(), anyString()))
                .thenReturn(new Reply("no json here", true, "stop", List.of(), USAGE));

        WebResearchService.Outcome out = service.research(req(ORG_A));

        assertThat(out.done()).isFalse();
        assertThat(out.reason()).isEqualTo("parse");
    }

    @Test
    void guardRejectionSendsNothing() {
        WebResearchService.Request withEmail = new WebResearchService.Request(ORG_A, "Paris contact@club.fr", "FR",
                "house & techno", null, List.of(OCT17));

        WebResearchService.Outcome out = service.research(withEmail);

        assertThat(out.done()).isFalse();
        assertThat(out.reason()).isEqualTo("guard");
        verify(client, never()).research(anyString(), anyString(), anyString());
    }
}
