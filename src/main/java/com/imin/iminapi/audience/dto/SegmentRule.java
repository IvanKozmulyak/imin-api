package com.imin.iminapi.audience.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One segment condition; {@code in} takes comma-separated values. */
@Schema(name = "SegmentRule")
public record SegmentRule(
        @Schema(description = "events | spend_minor | recency | no_show | nps | lifecycle | consent_status"
                + " | consent_basis | guest_class | genre | city | attended_event")
        String field,
        @Schema(description = ">= | <= | > | < | == | in")
        String operator,
        String value) {}
