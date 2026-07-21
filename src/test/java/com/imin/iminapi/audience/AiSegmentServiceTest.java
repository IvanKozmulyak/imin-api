package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.AiSegmentDraftResponse;
import com.imin.iminapi.audience.dto.SegmentDraftLlm;
import com.imin.iminapi.audience.dto.SegmentDraftLlm.Rule;
import com.imin.iminapi.audience.dto.SegmentResolveDto;
import com.imin.iminapi.audience.service.AiSegmentService;
import com.imin.iminapi.audience.service.SegmentRuleSchema;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the AI segment drafting pipeline (mocked ChatClient + mocked SegmentService):
 * <ul>
 *   <li>{@link SegmentRuleSchema} validation — valid shapes pass; unknown fields, bad operators
 *       and malformed values are stripped and reported.</li>
 *   <li>Happy path — a valid model draft becomes canonical rulesJson + explanation + preview counts.</li>
 *   <li>Clean degradation — garbage / all-unsupported / LLM-failure never 5xx and never a broken segment.</li>
 *   <li>A table of 10 realistic phrasings, run through the mapper+validator pipeline, to lock behaviour.</li>
 * </ul>
 */
class AiSegmentServiceTest {

    // Deep stubs cover chat.prompt().user(...).call().entity(SegmentDraftLlm.class).
    private final ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final SegmentService segmentService = mock(SegmentService.class);
    private final AiSegmentService sut = new AiSegmentService(chat, segmentService);

    private final UUID orgId = UUID.randomUUID();

    private AuthPrincipal principal() {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private void stubModel(SegmentDraftLlm out) {
        when(chat.prompt().user(anyString()).call().entity(SegmentDraftLlm.class)).thenReturn(out);
    }

    private void stubPreview(int matched, int mailable) {
        when(segmentService.previewRules(any(), anyString()))
                .thenReturn(new SegmentResolveDto(matched, mailable, matched - mailable, 5000L));
    }

    // ── SegmentRuleSchema: valid shapes pass ──────────────────────────────────

    @Test
    void validator_accepts_every_supported_field_and_operator() {
        SegmentRuleSchema.Result r = SegmentRuleSchema.validate(List.of(
                new Rule("events", ">=", "2"),
                new Rule("spend_minor", ">", "10000"),
                new Rule("recency", "<=", "180"),
                new Rule("no_show", ">", "0"),
                new Rule("nps", ">=", "9"),
                new Rule("lifecycle", "==", "vip"),
                new Rule("consent_status", "==", "subscribed"),
                new Rule("consent_basis", "==", "explicit")));

        assertThat(r.unsupported()).isEmpty();
        assertThat(r.rules()).hasSize(8);
        assertThat(SegmentRuleSchema.canonicalJson(r.rules()))
                .contains("\"field\":\"events\"").contains("\"operator\":\">=\"").contains("\"value\":\"2\"");
    }

    @Test
    void validator_strips_unknown_field_and_reports_it() {
        SegmentRuleSchema.Result r = SegmentRuleSchema.validate(List.of(
                new Rule("events", ">=", "2"),
                new Rule("total_revenue", ">", "100"),   // hallucinated field
                new Rule("genre", "==", "techno")));      // not filterable

        assertThat(r.rules()).extracting(SegmentRuleSchema.ValidRule::field).containsExactly("events");
        assertThat(r.unsupported()).hasSize(2);
        assertThat(r.unsupported()).anySatisfy(s -> assertThat(s).contains("total_revenue"));
        assertThat(r.unsupported()).anySatisfy(s -> assertThat(s).contains("genre"));
    }

    @Test
    void validator_rejects_non_numeric_value_and_bad_enum_value() {
        SegmentRuleSchema.Result r = SegmentRuleSchema.validate(List.of(
                new Rule("spend_minor", ">", "a lot"),       // not a number
                new Rule("lifecycle", "==", "superfan")));    // not in the enum

        assertThat(r.rules()).isEmpty();
        assertThat(r.unsupported()).hasSize(2);
    }

    @Test
    void validator_rejects_negation_and_ordering_on_enum_fields() {
        SegmentRuleSchema.Result r = SegmentRuleSchema.validate(List.of(
                new Rule("consent_status", "!=", "unsubscribed"),  // negation not expressible
                new Rule("lifecycle", ">", "repeat")));            // ordering meaningless for enum

        assertThat(r.rules()).isEmpty();
        assertThat(r.unsupported()).hasSize(2);
        assertThat(r.unsupported()).allSatisfy(s -> assertThat(s).contains("equality"));
    }

    @Test
    void validator_canonicalizes_operator_synonyms_and_currency_values() {
        SegmentRuleSchema.Result r = SegmentRuleSchema.validate(List.of(
                new Rule("events", "gte", "2"),
                new Rule("spend_minor", "greater_than", "€10,000")));

        assertThat(r.unsupported()).isEmpty();
        assertThat(r.rules()).containsExactly(
                new SegmentRuleSchema.ValidRule("events", ">=", "2"),
                new SegmentRuleSchema.ValidRule("spend_minor", ">", "10000"));
    }

    @Test
    void validator_explanations_are_human_and_match_rules() {
        assertThat(SegmentRuleSchema.explain(new SegmentRuleSchema.ValidRule("spend_minor", ">", "10000")))
                .isEqualTo("Spent more than €100 in total.");
        assertThat(SegmentRuleSchema.explain(new SegmentRuleSchema.ValidRule("events", ">=", "2")))
                .isEqualTo("Attended at least 2 events.");
        assertThat(SegmentRuleSchema.explain(new SegmentRuleSchema.ValidRule("recency", "<=", "180")))
                .isEqualTo("Purchased within the last 180 days.");
        assertThat(SegmentRuleSchema.explain(new SegmentRuleSchema.ValidRule("consent_status", "==", "subscribed")))
                .isEqualTo("Subscription status is subscribed.");
    }

    // ── Service happy path ────────────────────────────────────────────────────

    @Test
    void happy_path_builds_canonical_rules_explanation_and_preview_counts() {
        stubModel(new SegmentDraftLlm("Big Spenders",
                List.of(new Rule("spend_minor", ">=", "10000")), false, List.of()));
        stubPreview(42, 30);

        AiSegmentDraftResponse res = sut.draft(principal(), "everyone who spent over €100");

        assertThat(res.createAllowed()).isTrue();
        assertThat(res.name()).isEqualTo("Big Spenders");
        assertThat(res.rulesJson()).isEqualTo("[{\"field\":\"spend_minor\",\"operator\":\">=\",\"value\":\"10000\"}]");
        assertThat(res.rules()).hasSize(1);
        assertThat(res.explanationLines()).containsExactly("Spent at least €100 in total.");
        assertThat(res.explanation()).isEqualTo("Spent at least €100 in total.");
        assertThat(res.matchedCount()).isEqualTo(42);
        assertThat(res.mailableCount()).isEqualTo(30);
        assertThat(res.unsupported()).isEmpty();
    }

    @Test
    void all_contacts_yields_everyone_segment_with_empty_rules() {
        stubModel(new SegmentDraftLlm("All contacts", List.of(), true, List.of()));
        stubPreview(500, 380);

        AiSegmentDraftResponse res = sut.draft(principal(), "all my contacts");

        assertThat(res.createAllowed()).isTrue();
        assertThat(res.rulesJson()).isEqualTo("[]");
        assertThat(res.explanationLines()).containsExactly("All contacts (no filters).");
        assertThat(res.matchedCount()).isEqualTo(500);
    }

    @Test
    void partial_request_keeps_valid_rule_and_reports_unsupported_parts() {
        stubModel(new SegmentDraftLlm("VIPs",
                List.of(new Rule("lifecycle", "==", "vip")), false,
                List.of("techno fans", "from last summer")));
        stubPreview(12, 10);

        AiSegmentDraftResponse res = sut.draft(principal(), "VIP techno fans from last summer");

        assertThat(res.createAllowed()).isTrue();
        assertThat(res.rules()).hasSize(1);
        assertThat(res.explanationLines()).containsExactly("Lifecycle stage is Vip.");
        assertThat(res.unsupported()).contains("techno fans", "from last summer");
        assertThat(res.matchedCount()).isEqualTo(12);
    }

    // ── Clean degradation (never 5xx, never a broken segment) ─────────────────

    @Test
    void all_unsupported_degrades_to_empty_draft_with_create_blocked() {
        stubModel(new SegmentDraftLlm("House heads", List.of(), false,
                List.of("house music", "Berlin")));

        AiSegmentDraftResponse res = sut.draft(principal(), "fans of house music in Berlin");

        assertThat(res.createAllowed()).isFalse();
        assertThat(res.rulesJson()).isEqualTo("[]");
        assertThat(res.matchedCount()).isZero();
        assertThat(res.mailableCount()).isZero();
        assertThat(res.explanation()).isEmpty();
        assertThat(res.unsupported()).contains("house music", "Berlin");
        // Preview must NOT run for an unfulfillable draft — no phantom "everyone" count.
        verify(segmentService, never()).previewRules(any(), anyString());
    }

    @Test
    void garbage_model_output_of_only_unknown_fields_is_stripped_and_blocked() {
        stubModel(new SegmentDraftLlm(null,
                List.of(new Rule("favourite_colour", "==", "blue"),
                        new Rule(null, null, null)),
                false, null));

        AiSegmentDraftResponse res = sut.draft(principal(), "people who like blue");

        assertThat(res.createAllowed()).isFalse();
        assertThat(res.rules()).isEmpty();
        assertThat(res.unsupported()).isNotEmpty();
        assertThat(res.name()).isEqualTo("AI segment"); // deterministic fallback name
    }

    @Test
    void llm_failure_degrades_cleanly_without_throwing() {
        when(chat.prompt().user(anyString()).call().entity(SegmentDraftLlm.class))
                .thenThrow(new RuntimeException("upstream down"));

        AiSegmentDraftResponse res = sut.draft(principal(), "everyone who spent over €100");

        assertThat(res.createAllowed()).isFalse();
        assertThat(res.rulesJson()).isEqualTo("[]");
        assertThat(res.unsupported()).isNotEmpty(); // a helpful reason is always present
        verify(segmentService, never()).previewRules(any(), anyString());
    }

    @Test
    void null_model_output_degrades_cleanly() {
        stubModel(null);

        AiSegmentDraftResponse res = sut.draft(principal(), "whatever");

        assertThat(res.createAllowed()).isFalse();
        assertThat(res.unsupported()).isNotEmpty();
    }

    // ── Table-driven: 10 realistic phrasings → expected pipeline outcome ───────

    private record Phrasing(String prompt, SegmentDraftLlm model,
                            int expectedValidRules, boolean expectedCreateAllowed,
                            boolean expectedUnsupported) {}

    @Test
    void table_of_realistic_phrasings_locks_pipeline_behaviour() {
        stubPreview(20, 15);
        List<Phrasing> cases = List.of(
                // 1. simple spend threshold — fully supported
                new Phrasing("everyone who spent over €100",
                        new SegmentDraftLlm("Big spenders", List.of(new Rule("spend_minor", ">", "10000")), false, List.of()),
                        1, true, false),
                // 2. repeat attendance — fully supported
                new Phrasing("people who attended at least 2 events",
                        new SegmentDraftLlm("Repeat attendees", List.of(new Rule("events", ">=", "2")), false, List.of()),
                        1, true, false),
                // 3. whole audience — everyone
                new Phrasing("all my contacts",
                        new SegmentDraftLlm("All contacts", List.of(), true, List.of()),
                        0, true, false),
                // 4. VIP + unsupported vibe/date — partial
                new Phrasing("VIP techno fans from last summer",
                        new SegmentDraftLlm("VIPs", List.of(new Rule("lifecycle", "==", "vip")), false,
                                List.of("techno fans", "from last summer")),
                        1, true, true),
                // 5. lapsed subscribers — two supported rules
                new Phrasing("subscribers who haven't bought in over 3 months",
                        new SegmentDraftLlm("Lapsed subscribers",
                                List.of(new Rule("consent_status", "==", "subscribed"), new Rule("recency", ">=", "90")),
                                false, List.of()),
                        2, true, false),
                // 6. promoters — supported
                new Phrasing("promoters who rated us 9 or 10",
                        new SegmentDraftLlm("Promoters", List.of(new Rule("nps", ">=", "9")), false, List.of()),
                        1, true, false),
                // 7. bought but no-showed — supported
                new Phrasing("folks who bought but didn't show up",
                        new SegmentDraftLlm("No-shows", List.of(new Rule("no_show", ">", "0")), false, List.of()),
                        1, true, false),
                // 8. genre + city — fully unsupported
                new Phrasing("fans of house music in Berlin",
                        new SegmentDraftLlm("House heads", List.of(), false, List.of("house music", "Berlin")),
                        0, false, true),
                // 9. spend + email-open — partial (email opens not filterable)
                new Phrasing("people who spent more than €50 and opened my last newsletter",
                        new SegmentDraftLlm("Engaged spenders", List.of(new Rule("spend_minor", ">", "5000")), false,
                                List.of("opened my last newsletter")),
                        1, true, true),
                // 10. hallucinated field name — stripped, blocked
                new Phrasing("my biggest fans",
                        new SegmentDraftLlm("Biggest fans", List.of(new Rule("loyalty_score", ">", "80")), false, List.of()),
                        0, false, true)
        );

        for (Phrasing c : cases) {
            stubModel(c.model());
            AiSegmentDraftResponse res = sut.draft(principal(), c.prompt());
            assertThat(res.rules())
                    .as("valid rule count for: %s", c.prompt())
                    .hasSize(c.expectedValidRules());
            assertThat(res.createAllowed())
                    .as("createAllowed for: %s", c.prompt())
                    .isEqualTo(c.expectedCreateAllowed());
            assertThat(!res.unsupported().isEmpty())
                    .as("has unsupported for: %s", c.prompt())
                    .isEqualTo(c.expectedUnsupported());
            // Invariant across every phrasing: we never emit a rulesJson the engine can't parse,
            // and confirm is only offered when there is something real to create.
            assertThat(res.rulesJson()).startsWith("[");
            if (!res.createAllowed()) {
                assertThat(res.matchedCount()).isZero();
            }
        }
    }
}
