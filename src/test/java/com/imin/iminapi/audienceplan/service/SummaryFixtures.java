package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse.Action;
import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse.ArmDate;
import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse.Segment;
import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse.SegmentReason;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The checked warm fixture (§5) as a plan response, and a ChatClient that replays recorded OpenRouter answers. */
final class SummaryFixtures {

    static final String BASE_URL = "https://openrouter.test";
    static final LocalDate TODAY = LocalDate.of(2026, 9, 26);
    static final LocalDate EVENT = LocalDate.of(2026, 10, 24);
    static final LocalDate D3 = LocalDate.of(2026, 10, 21);
    static final String HOUSE = "house & techno";

    private SummaryFixtures() {}

    static AudiencePlanResponse warm() {
        return plan("warm", 345, new AudiencePlanResponse.TicketRange(26, 52, 93),
                new AudiencePlanResponse.Coverage(0.10, 0.20, 0.36, "medium"), 162, 229,
                List.of(segment("loyal", 40, 0.12, 0.25, 0.40, 8, 16, 26, 3, null),
                        segment("repeat", 70, 0.06, 0.12, 0.20, 7, 13, 22, 2, 2),
                        segment("first_timer", 235, 0.03, 0.06, 0.12, 11, 23, 45, 1, 1)),
                List.of(invite("loyal", null), invite("repeat", null), invite("first_timer", 15)), List.of());
    }

    /** No mailable list: expected and coverage ratios are null, the whole target is the gap. */
    static AudiencePlanResponse cold() {
        return plan("cold", 0, null, new AudiencePlanResponse.Coverage(null, null, null, "cold"), 255, 255,
                List.of(), List.of(new Action("import_with_proof", null, null, List.of(), null, List.of())),
                List.of());
    }

    static AudiencePlanResponse withActions(AudiencePlanResponse p, List<Action> actions) {
        return new AudiencePlanResponse(p.id(), p.eventId(), p.mode(), p.capacity(), p.targetTickets(), p.mailable(),
                p.expected(), p.coverage(), p.gap(), p.reachNeeded(), p.gapExceedsTribe(), p.segments(),
                p.smallGroupsNotShown(), p.otherGenreInvited(), p.otherGenreHeldBack(), p.exclusions(), p.timing(),
                p.newPeople(), actions, p.assumptions(), p.summary(), p.versions(), p.createdAt());
    }

    /** The same plan with each segment's confidence replaced, in order. */
    static AudiencePlanResponse withConfidence(AudiencePlanResponse p, String... levels) {
        List<Segment> segs = new java.util.ArrayList<>();
        for (int i = 0; i < p.segments().size(); i++) {
            Segment s = p.segments().get(i);
            segs.add(new Segment(s.classKey(), s.genreFit(), s.mailable(), s.rate(), s.ticketsPerOrder(), s.expected(),
                    levels[i], s.reason()));
        }
        return new AudiencePlanResponse(p.id(), p.eventId(), p.mode(), p.capacity(), p.targetTickets(), p.mailable(),
                p.expected(), p.coverage(), p.gap(), p.reachNeeded(), p.gapExceedsTribe(), List.copyOf(segs),
                p.smallGroupsNotShown(), p.otherGenreInvited(), p.otherGenreHeldBack(), p.exclusions(), p.timing(),
                p.newPeople(), p.actions(), p.assumptions(), p.summary(), p.versions(), p.createdAt());
    }

    static AudiencePlanResponse withNewPeople(AudiencePlanResponse p, List<AudiencePortraitResponse.NewPeopleGroup> groups) {
        return new AudiencePlanResponse(p.id(), p.eventId(), p.mode(), p.capacity(), p.targetTickets(), p.mailable(),
                p.expected(), p.coverage(), p.gap(), p.reachNeeded(), p.gapExceedsTribe(), p.segments(),
                p.smallGroupsNotShown(), p.otherGenreInvited(), p.otherGenreHeldBack(), p.exclusions(), p.timing(),
                groups, p.actions(), p.assumptions(), p.summary(), p.versions(), p.createdAt());
    }

    /** A new-people group; null bounds = unknown size. */
    static AudiencePortraitResponse.NewPeopleGroup group(String key, String kind, Integer low, Integer high) {
        return new AudiencePortraitResponse.NewPeopleGroup(key, "open_data", kind, "fr_catchment", List.of("metz"),
                low == null ? null : new AudiencePortraitResponse.SizeRange(low, high), "electronic_first", List.of(), null);
    }

    static AudiencePlanResponse withGap(AudiencePlanResponse p, int low, int high, List<String> excluded) {
        return new AudiencePlanResponse(p.id(), p.eventId(), p.mode(), p.capacity(), p.targetTickets(), p.mailable(),
                p.expected(), p.coverage(), new AudiencePlanResponse.Gap(low, high), p.reachNeeded(),
                p.gapExceedsTribe(), p.segments(), p.smallGroupsNotShown(), p.otherGenreInvited(),
                p.otherGenreHeldBack(), p.exclusions(), p.timing(), p.newPeople(), p.actions(),
                new AudiencePlanResponse.Assumptions(85, 1.6, excluded), p.summary(), p.versions(), p.createdAt());
    }

    private static AudiencePlanResponse plan(String mode, int mailable, AudiencePlanResponse.TicketRange expected,
                                             AudiencePlanResponse.Coverage coverage, int gapLow, int gapHigh,
                                             List<Segment> segments, List<Action> actions, List<String> excluded) {
        Map<String, Integer> exclusions = new LinkedHashMap<>();
        exclusions.put("legacy_unproven", 12);
        exclusions.put("unsubscribed", 3);
        AudiencePlanResponse.Reach unknown = new AudiencePlanResponse.Reach("unknown", null, null);
        return new AudiencePlanResponse(UUID.randomUUID(), UUID.randomUUID(), mode, 300, 255, mailable, expected,
                coverage, new AudiencePlanResponse.Gap(gapLow, gapHigh),
                new AudiencePlanResponse.ReachNeeded(new AudiencePlanResponse.Reach("unverified", null, null), unknown),
                null, segments, 0, false, 0, exclusions,
                new AudiencePlanResponse.Timing(TODAY, EVENT, TODAY, D3, 28, false), List.of(), actions,
                new AudiencePlanResponse.Assumptions(85, 1.6, excluded), null,
                new AudiencePlanResponse.Versions(1, 1, null), Instant.parse("2026-09-26T08:00:00Z"));
    }

    private static Segment segment(String cls, int mailable, double rl, double rm, double rh, int el, int em, int eh,
                                   Integer ordersMin, Integer ordersMax) {
        return new Segment(cls, "same", mailable, new AudiencePlanResponse.Rate(rl, rm, rh), 1.6,
                new AudiencePlanResponse.TicketRange(el, em, eh), "prior",
                new SegmentReason(ordersMin, ordersMax, null, 90, null, false, HOUSE, "same"));
    }

    private static Action invite(String cls, Integer holdoutPct) {
        return new Action("invite", cls, "same", List.of(new ArmDate("launch", TODAY), new ArmDate("d3", D3)),
                holdoutPct, List.of());
    }

    /** A real OpenAI-compatible chat model whose HTTP calls hit {@code server} instead of OpenRouter. */
    static ChatClient replaying(RestClient.Builder http) {
        OpenAiApi api = OpenAiApi.builder().baseUrl(BASE_URL).apiKey("test-key-not-real").restClientBuilder(http).build();
        OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("platform/default-model").build()).build();
        return ChatClient.builder(model).build();
    }

    static void expectAnswer(MockRestServiceServer server, String recording) {
        server.expect(requestTo(BASE_URL + "/v1/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(recorded(recording), MediaType.APPLICATION_JSON));
    }

    static String recorded(String name) {
        try {
            return new ClassPathResource("audienceplan/summarizer/" + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
