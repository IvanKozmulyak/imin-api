package com.imin.iminapi.audience.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A group of segment rules; a segment's groups are ANDed together. {@code and}: every rule matches,
 * {@code or}: at least one, {@code not}: none of them.
 */
@Schema(name = "SegmentRuleGroup")
public record SegmentRuleGroup(
        @Schema(allowableValues = {"and", "or", "not"}) String combinator,
        List<SegmentRule> rules) {}
