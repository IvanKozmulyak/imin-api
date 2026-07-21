package com.imin.iminapi.audience.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/audience/segments}.
 *
 * <p>{@code rulesJson} is intentionally typed as {@link Object} so BOTH caller shapes bind
 * cleanly through the HTTP message converter:
 * <ul>
 *   <li>a structured array — {@code "rulesJson": [{"field":"events","operator":">=","value":"2"}]}
 *       (what the dashboard's segment form sends) binds to a {@code List}, and</li>
 *   <li>a pre-serialized JSON string — {@code "rulesJson": "[{...}]"} binds to a {@code String}.</li>
 * </ul>
 * The previous controller bound the whole body as {@code Map<String,String>}, so an object/array
 * {@code rulesJson} tripped Jackson with an unreadable-body 400 — a big part of the "intermittently
 * blocked" report. {@link #rulesJsonAsString()} canonicalizes either shape to the string the
 * {@code segments.rules_json} column stores. Rule <em>semantics</em> (known fields/operators) are
 * validated in {@code SegmentService}, where the rule engine lives.
 *
 * <p>Not a {@code JsonNode}: Spring Boot 4's HTTP converter is Jackson 3 and cannot bind into the
 * Jackson 2 {@code JsonNode} still used elsewhere in this module — {@code Object} sidesteps that.
 */
public record CreateSegmentRequest(
        @NotBlank(message = "Segment name is required")
        @Size(max = 128, message = "Segment name must be 128 characters or fewer")
        String name,

        String kind,

        Object rulesJson
) {

    private static final ObjectMapper CANONICAL = new ObjectMapper();

    /** dynamic (default) or static; anything else is coerced to dynamic. */
    public String kindOrDefault() {
        return "static".equals(kind) ? "static" : "dynamic";
    }

    /**
     * Canonical {@code rules_json} string to persist, or {@code null} when no rules were
     * supplied (which the engine treats as "everyone"). A string body is passed through
     * verbatim; a structured body is re-serialized to canonical JSON. Anything that somehow
     * can't be re-serialized falls through as text and is rejected later by the service's
     * rule validation as a clean 400 (never a 500).
     */
    public String rulesJsonAsString() {
        if (rulesJson == null) return null;
        if (rulesJson instanceof String s) {
            return s.isBlank() ? null : s;
        }
        try {
            return CANONICAL.writeValueAsString(rulesJson);
        } catch (Exception e) {
            return String.valueOf(rulesJson);
        }
    }
}
