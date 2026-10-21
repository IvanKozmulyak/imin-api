package com.imin.iminapi.marketing.service;

import java.util.UUID;

/**
 * A campaign reached a terminal state by its own send: finished ({@code sent}) or stopped with an error
 * ({@code failed}, with the closed failure code). Published only after the winning status flip.
 */
public record CampaignEnded(UUID campaignId, UUID orgId, String outcome, String failureCode) {

    public static final String SENT = "sent";
    public static final String FAILED = "failed";

    public static CampaignEnded sent(UUID campaignId, UUID orgId) {
        return new CampaignEnded(campaignId, orgId, SENT, null);
    }

    public static CampaignEnded failed(UUID campaignId, UUID orgId, String failureCode) {
        return new CampaignEnded(campaignId, orgId, FAILED, failureCode);
    }
}
