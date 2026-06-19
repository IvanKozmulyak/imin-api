package com.imin.iminapi.audience.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.Segment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SegmentDto(
        UUID id,
        UUID orgId,
        String name,
        String kind,
        boolean prebuilt,
        List<Map<String, String>> rules,
        List<String> rulesSummary,
        int liveCount,
        Instant createdAt
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, String>>> RULES_TYPE = new TypeReference<>() {};

    public static SegmentDto from(Segment s, int liveCount) {
        List<Map<String, String>> rules = List.of();
        List<String> summary = List.of();
        try {
            if (s.getRulesJson() != null) {
                rules = MAPPER.readValue(s.getRulesJson(), RULES_TYPE);
                summary = rules.stream()
                        .map(r -> r.getOrDefault("field", "") + " " + r.getOrDefault("operator", "") + " " + r.getOrDefault("value", ""))
                        .toList();
            }
        } catch (Exception ignored) {}
        return new SegmentDto(s.getId(), s.getOrgId(), s.getName(), s.getKind(),
                s.isPrebuilt(), rules, summary, liveCount, s.getCreatedAt());
    }
}
