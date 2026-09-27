package com.imin.iminapi.audience.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body for {@code POST /audience/segments/preview}; {@code rulesJson} binds like {@link CreateSegmentRequest}'s. */
public record SegmentPreviewRequest(
        @Schema(description = "A rule array (one AND group) or {\"groups\": [SegmentRuleGroup]}; absent = everyone")
        Object rulesJson) {

    public String rulesJsonAsString() {
        return new CreateSegmentRequest("preview", null, rulesJson).rulesJsonAsString();
    }
}
