package com.imin.iminapi.audience.dto;

import java.util.List;
import java.util.Map;

/**
 * A validated, preview-resolved AI segment draft returned for the organizer to confirm.
 *
 * <ul>
 *   <li>{@code name} — a suggested (editable) segment name.</li>
 *   <li>{@code rulesJson} — the canonical rules string to send verbatim to
 *       {@code POST /audience/segments} on confirm ({@code "[]"} means "all contacts").</li>
 *   <li>{@code rules} — the same rules parsed, for rendering chips/summary (mirrors {@code SegmentDto.rules}).</li>
 *   <li>{@code explanation} / {@code explanationLines} — deterministic human sentences built from the
 *       VALIDATED rules (one per filter), never the model's free-text — so the copy always matches the rules.</li>
 *   <li>{@code matchedCount} / {@code mailableCount} — live preview counts resolved server-side without persisting.</li>
 *   <li>{@code unsupported} — request parts that could not be expressed in our schema (dropped, not invented).</li>
 *   <li>{@code createAllowed} — false when nothing valid could be derived; the client must not offer "Confirm".</li>
 * </ul>
 */
public record AiSegmentDraftResponse(
        String name,
        String rulesJson,
        List<Map<String, String>> rules,
        String explanation,
        List<String> explanationLines,
        int matchedCount,
        int mailableCount,
        List<String> unsupported,
        boolean createAllowed) {}
