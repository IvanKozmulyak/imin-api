package com.imin.iminapi.predictor.service;

import java.util.UUID;

/**
 * Lightweight domain events the predictor reacts to from the marketing send path (task §4
 * trigger wiring). Published AFTER_COMMIT from {@code CampaignSendUnit} / {@code CampaignService}
 * so a live re-forecast reflects a campaign's activity — without the marketing path knowing
 * anything about the re-forecast engine. {@link ReforecastTriggerService} listens and routes to
 * its debounced recompute. Mirrors {@link PredictorReactivityEvents} (event-mutation path).
 */
public final class PredictorMarketingEvents {

    private PredictorMarketingEvents() {}

    /** A campaign finished sending for this event (sales may have moved) → CAMPAIGN_SEND recompute. */
    public record CampaignSent(UUID eventId) {}

    /** A campaign was scheduled to send for this event → CAMPAIGN_SCHEDULED recompute. */
    public record CampaignScheduled(UUID eventId) {}
}
