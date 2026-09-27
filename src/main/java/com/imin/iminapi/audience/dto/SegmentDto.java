package com.imin.iminapi.audience.dto;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.service.SegmentRules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code ruleGroups} is the segment's full grammar (a legacy rule array is one {@code and} group);
 * {@code rules} lists the rules only when they form a single {@code and} group, and is empty otherwise.
 */
public record SegmentDto(
        UUID id,
        UUID orgId,
        String name,
        String kind,
        boolean prebuilt,
        List<Map<String, String>> rules,
        List<SegmentRuleGroup> ruleGroups,
        List<String> rulesSummary,
        int liveCount,
        Instant createdAt
) {
    public static SegmentDto from(Segment s, int liveCount) {
        SegmentRules.Parsed parsed = SegmentRules.parse(s.getRulesJson());
        List<Map<String, String>> rules = List.of();
        List<SegmentRuleGroup> groups = List.of();
        List<String> summary = List.of();
        if (parsed != null) {
            groups = parsed.asDto();
            boolean flat = parsed.groups().size() == 1
                    && parsed.groups().get(0).combinator() == SegmentRules.Combinator.AND;
            if (flat) {
                rules = parsed.groups().get(0).rules().stream().map(SegmentDto::asMap).toList();
                summary = parsed.groups().get(0).rules().stream().map(SegmentDto::line).toList();
            } else {
                List<String> lines = new ArrayList<>();
                for (SegmentRules.Group g : parsed.groups()) {
                    if (g.rules().isEmpty()) continue;
                    lines.add(g.combinator().key() + ": "
                            + String.join("; ", g.rules().stream().map(SegmentDto::line).toList()));
                }
                summary = List.copyOf(lines);
            }
        }
        return new SegmentDto(s.getId(), s.getOrgId(), s.getName(), s.getKind(),
                s.isPrebuilt(), rules, groups, summary, liveCount, s.getCreatedAt());
    }

    private static Map<String, String> asMap(SegmentRules.Rule r) {
        return Map.of("field", nz(r.field()), "operator", nz(r.operator()), "value", nz(r.value()));
    }

    private static String line(SegmentRules.Rule r) {
        return nz(r.field()) + " " + nz(r.operator()) + " " + nz(r.value());
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
