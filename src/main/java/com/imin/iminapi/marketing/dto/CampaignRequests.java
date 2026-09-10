package com.imin.iminapi.marketing.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request bodies for the campaign endpoints (spec §2.4).
 *
 * <p>mkt-edge-7: the {@code @Size} bounds below mirror the columns these land in
 * (V52 {@code subject}/{@code preheader} VARCHAR(200), V66 {@code template_key} VARCHAR(64)).
 * Without them an over-long value reached Postgres as a 22001 string-data overflow, which
 * {@code GlobalExceptionHandler} can only render as a fieldless 400 — the composer could not
 * point at the offending field. {@code name} is deliberately unconstrained: CampaignService
 * silently truncates it to 120 today, so a bound here would turn a request that currently
 * succeeds into a 400.
 */
public final class CampaignRequests {

    private CampaignRequests() {}

    /** POST /campaigns — fired on composer step-1 submit. name + channel are required. */
    public record CreateCampaignRequest(
            String channel,
            String name,
            UUID segmentId,
            UUID eventId,
            @Size(max = 200, message = "must be at most 200 characters") String subject,
            @Size(max = 200, message = "must be at most 200 characters") String preheader,
            String bodyMd,
            /** Email template key (V66): builtin key or saved-template UUID. Null → 'classic'. */
            @Size(max = 64, message = "must be at most 64 characters") String templateKey
    ) {}

    /**
     * PATCH /campaigns/{id} — partial; an absent field is left unchanged. Draft-only.
     *
     * <p>{@code segmentId} and {@code eventId} are {@link PatchableUuid} rather than plain
     * UUIDs so an explicit {@code null} can CLEAR the link (mkt-edge-8) while an absent field
     * still means "unchanged"; a plain UUID collapses those two requests into one value. The
     * wire shape is unchanged — see {@link PatchableUuid}.
     */
    public record PatchCampaignRequest(
            String name,
            @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = UUID.class, nullable = true,
                    description = "Segment to target. Explicit null unlinks the segment; omit to leave unchanged.")
            PatchableUuid segmentId,
            @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = UUID.class, nullable = true,
                    description = "Event to link. Explicit null unlinks the event; omit to leave unchanged.")
            PatchableUuid eventId,
            @Size(max = 200, message = "must be at most 200 characters") String subject,
            @Size(max = 200, message = "must be at most 200 characters") String preheader,
            String bodyMd,
            /** Email template key (V66) — applied only when non-null (draft-only, like the rest). */
            @Size(max = 64, message = "must be at most 64 characters") String templateKey
    ) {}

    /** POST /campaigns/{id}/test-send. When email is null the caller's own address is used. */
    public record TestSendRequest(String email) {}
}
