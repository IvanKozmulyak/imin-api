package com.imin.iminapi.audience.dto;

import java.util.List;

/**
 * Raw, UNTRUSTED structured output from the segment-drafting LLM. The model proposes a name,
 * a list of {@code {field, operator, value}} rules drawn from our fixed schema, whether the
 * organizer meant "everyone" (no filter), and which parts of the request it could NOT express.
 *
 * <p>Nothing here reaches a persisted segment without first passing {@code SegmentRuleSchema}
 * validation — unknown fields, bad operators and malformed values are stripped and reported,
 * never silently trusted.
 *
 * <p>Top-level record (matching {@code ConceptSet} / {@code SubjectVariantsLlm}) so Spring AI's
 * {@code BeanOutputConverter} can derive its JSON schema and bind it via
 * {@code .entity(SegmentDraftLlm.class)}. Every field is nullable — the service null-guards all of it.
 */
public record SegmentDraftLlm(
        String name,
        List<Rule> rules,
        Boolean allContacts,
        List<String> unsupported) {

    public record Rule(String field, String operator, String value) {}
}
