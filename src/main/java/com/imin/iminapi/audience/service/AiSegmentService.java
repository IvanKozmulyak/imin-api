package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.dto.AiSegmentDraftResponse;
import com.imin.iminapi.audience.dto.SegmentDraftLlm;
import com.imin.iminapi.audience.dto.SegmentResolveDto;
import com.imin.iminapi.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Turns an organizer's natural-language audience description into VALIDATED segment filters plus
 * a live size preview — without persisting anything. The organizer reviews the draft and confirms
 * through the normal {@code POST /audience/segments} create flow.
 *
 * <p><b>Never trusts raw model output.</b> The single LLM call returns a proposed
 * {@link SegmentDraftLlm}; every rule is then run through {@link SegmentRuleSchema#validate} before
 * it can reach {@code rulesJson}. Anything the schema can't express is stripped and reported in
 * {@code unsupported}, never invented into a filter.
 *
 * <p><b>Degrades cleanly, never 5xx.</b> A model that fails, returns nothing usable, or proposes
 * only unsupported filters yields a 200 with empty rules, {@code createAllowed=false}, and the
 * reasons — so the caller shows a clear "couldn't build that" message instead of a broken segment.
 * (Blank/oversize prompt input is a separate concern, rejected up-front by {@code @Valid}.)
 *
 * <p><b>One paid call per request.</b> No retry loop; the controller rate-limits on the shared
 * {@code ai-concept} burst bucket.
 */
@Service
public class AiSegmentService {

    private static final Logger log = LoggerFactory.getLogger(AiSegmentService.class);
    private static final int MAX_NAME = 60;

    private final ChatClient chat;
    private final SegmentService segmentService;

    public AiSegmentService(ChatClient chat, SegmentService segmentService) {
        this.chat = chat;
        this.segmentService = segmentService;
    }

    public AiSegmentDraftResponse draft(AuthPrincipal p, String prompt) {
        SegmentDraftLlm llm = callModel(prompt);

        SegmentRuleSchema.Result result = SegmentRuleSchema.validate(llm == null ? null : llm.rules());
        List<SegmentRuleSchema.ValidRule> valid = result.rules();

        // Merge the model's own "couldn't express" list with the parts the validator stripped.
        List<String> unsupported = mergeUnsupported(llm, result.unsupported());

        boolean everyone = valid.isEmpty() && llm != null && Boolean.TRUE.equals(llm.allContacts());
        boolean createAllowed = !valid.isEmpty() || everyone;

        // When nothing valid could be derived, make sure the caller gets at least one reason.
        if (!createAllowed && unsupported.isEmpty()) {
            unsupported = List.of("Couldn't map that description to any available filter. Try describing "
                    + "total spend, events attended, recency, NPS score, lifecycle stage, or subscription status.");
        }

        String rulesJson = SegmentRuleSchema.canonicalJson(valid); // "[]" when empty
        List<Map<String, String>> ruleMaps = SegmentRuleSchema.asMaps(valid);

        List<String> lines = new ArrayList<>();
        int matched = 0;
        int mailable = 0;
        if (createAllowed) {
            // Resolve the DRAFT rules against real memberships without persisting a segment.
            SegmentResolveDto preview = segmentService.previewRules(p.orgId(), rulesJson);
            matched = preview.matched();
            mailable = preview.mailable();
            if (everyone) {
                lines.add("All contacts (no filters).");
            } else {
                for (SegmentRuleSchema.ValidRule r : valid) {
                    lines.add(SegmentRuleSchema.explain(r));
                }
            }
        }
        String explanation = String.join(" ", lines);
        String name = suggestName(llm, everyone);

        // Quality-debugging trail (spec: log prompt -> generated filters, structured, with user/org).
        log.info("ai-segment-draft userId={} orgId={} promptLen={} createAllowed={} rules={} unsupported={} matched={} mailable={}",
                p.actorLabel(), p.orgId(),
                prompt == null ? 0 : prompt.length(),
                createAllowed, rulesJson, unsupported, matched, mailable);

        return new AiSegmentDraftResponse(name, rulesJson, ruleMaps, explanation, lines,
                matched, mailable, unsupported, createAllowed);
    }

    /** Single, non-looping LLM call. Any failure degrades to a null draft (never a 5xx). */
    private SegmentDraftLlm callModel(String prompt) {
        try {
            return chat.prompt().user(buildPrompt(prompt)).call().entity(SegmentDraftLlm.class);
        } catch (Exception e) {
            log.warn("Segment-draft LLM call failed; degrading to empty draft: {}", e.getMessage());
            return null;
        }
    }

    private static List<String> mergeUnsupported(SegmentDraftLlm llm, List<String> stripped) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (llm != null && llm.unsupported() != null) {
            for (String s : llm.unsupported()) {
                if (s != null && !s.isBlank()) out.add(s.trim());
            }
        }
        out.addAll(stripped);
        return new ArrayList<>(out);
    }

    private static String suggestName(SegmentDraftLlm llm, boolean everyone) {
        String raw = llm == null ? null : llm.name();
        if (raw != null && !raw.isBlank()) {
            String n = raw.trim();
            return n.length() > MAX_NAME ? n.substring(0, MAX_NAME).trim() : n;
        }
        return everyone ? "All contacts" : "AI segment";
    }

    /**
     * The schema-constrained prompt. The model may ONLY emit fields/operators/values listed here;
     * anything else it must place in {@code unsupported} rather than invent a filter.
     */
    private String buildPrompt(String description) {
        return """
                You convert an event organizer's plain-language audience description into STRUCTURED
                FILTERS for our contact database. You may ONLY use the fields, operators and values
                listed below. If part of the request cannot be expressed with these fields, DO NOT
                invent a filter — list that part in "unsupported" instead.

                NUMERIC fields (operators: >=, <=, >, <, ==; value is a whole number as a string):
                - events        number of events the contact bought a ticket for
                - spend_minor   lifetime spend in CENTS (euros x 100 — "€100" is "10000")
                - recency       days since the contact's last purchase (SMALLER = more recent;
                                "in the last 6 months" -> recency <= 180; "inactive 90+ days" -> recency >= 90)
                - no_show       number of events the contact bought for but did NOT attend
                - nps           the contact's NPS score, 0-10 (promoters are nps >= 9)

                ENUM fields (equality only — operator "=="; value must be EXACTLY one of the listed):
                - lifecycle       one of: prospect, firsttime, repeat, vip, lapsing, dormant, wonback
                - consent_status  one of: never, subscribed, unsubscribed
                - consent_basis   one of: explicit, soft_opt_in

                All rules are combined with AND (every rule must match). We CANNOT express: OR
                conditions, negation / "not", genre or music taste, city or location, email opens or
                clicks, or any date other than recency-in-days. Put any such request part in
                "unsupported" using the organizer's own words.

                If the organizer means their whole audience with no filter (e.g. "everyone", "all my
                contacts"), set "allContacts" to true and return an empty "rules" array.

                Return JSON ONLY, matching exactly:
                {
                  "name": "<short segment name, max 5 words>",
                  "rules": [ { "field": "<field>", "operator": "<operator>", "value": "<value>" } ],
                  "allContacts": <true or false>,
                  "unsupported": [ "<request part you could not express>" ]
                }

                Organizer's description:
                %s
                """.formatted(description == null ? "" : description.trim());
    }
}
