package com.imin.iminapi.audienceplan.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code phase}: {@code live} before door close (computed, never stored), {@code pending} until the first collection
 * (no counts), else the stored {@code d1}/{@code d7}. Rates are ranges at {@code intervalLevel}; no open or click.
 */
public record AudiencePlanOutcomeResponse(
        UUID eventId,
        String phase,
        /* the event has audience experiments; tells a pending "results tomorrow" from "never invited" */
        boolean invited,
        Instant doorClosesAt,
        /* null for live and pending */
        Instant computedAt,
        double intervalLevel,
        /* arm and holdout members needed before a lift range is given */
        int minimumForLift,
        /* buyers with no membership in the org before the first invitation; null when not known yet */
        Integer newGuests,
        /* earliest scheduled arm campaign; live phase only */
        Instant nextWave,
        List<Segment> segments) {

    /**
     * One invited plan segment; {@code plannedRate} is the plan's purchase-rate band when it was invited and
     * {@code plannedConfidence} its source ({@code own | imin | prior}); both null without a plan segment.
     */
    @Schema(name = "AudiencePlanOutcomeSegment")
    public record Segment(UUID planSegmentId, String classKey, String genreFit, Range plannedRate,
                          String plannedConfidence, List<Arm> arms) {}

    /**
     * One arm (holdout first). {@code liftStatus}: {@code ok} (lift is a range), {@code too_few} (arm or holdout under
     * the minimum), {@code no_holdout}, or {@code baseline} for the holdout row. {@code lift} is null unless ok.
     */
    @Schema(name = "AudiencePlanOutcomeArm")
    public record Arm(String arm, UUID experimentId, int members, int sent, int bought, int tickets, int attended,
                      int unsubscribed, int complained, Range responseRate, Range lift, String liftStatus) {}

    /** A share or a difference of shares; {@code mid} is the observed value, shown only as a tick in the range. */
    @Schema(name = "AudiencePlanOutcomeRange")
    public record Range(double low, double mid, double high) {}
}
