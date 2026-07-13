package com.imin.iminapi.marketing.dto;

import java.util.UUID;

/** Request bodies for the campaign endpoints (spec §2.4). */
public final class CampaignRequests {

    private CampaignRequests() {}

    /** POST /campaigns — fired on composer step-1 submit. name + channel are required. */
    public record CreateCampaignRequest(
            String channel,
            String name,
            UUID segmentId,
            UUID eventId,
            String subject,
            String preheader,
            String bodyMd
    ) {}

    /** PATCH /campaigns/{id} — partial; only non-null fields are applied. Draft-only. */
    public record PatchCampaignRequest(
            String name,
            UUID segmentId,
            UUID eventId,
            String subject,
            String preheader,
            String bodyMd
    ) {}

    /** POST /campaigns/{id}/test-send. When email is null the caller's own address is used. */
    public record TestSendRequest(String email) {}
}
