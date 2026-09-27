package com.imin.iminapi.audience.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/** Segment counts; {@code mailable} and {@code exclusions} come from ConsentGate, and the reasons sum to {@code excluded}. */
public record SegmentResolveDto(
        int matched,
        int mailable,
        int excluded,
        long avgLtvMinor,
        @Schema(description = "Every ConsentGate reason with its count (0 when none): erase_pending, no_email,"
                + " unsubscribed, suppressed, objected, no_basis, legacy_unproven, retention_3y")
        Map<String, Integer> exclusions) {}
