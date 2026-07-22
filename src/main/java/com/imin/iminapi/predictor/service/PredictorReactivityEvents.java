package com.imin.iminapi.predictor.service;

import java.util.UUID;

/**
 * Lightweight domain events the predictor reacts to (task scope B — score reactivity). Published
 * AFTER_COMMIT from the event mutation path so a score/re-forecast reflects what the organizer
 * just did, without the mutation path knowing anything about the predictor.
 */
public final class PredictorReactivityEvents {

    private PredictorReactivityEvents() {}

    /**
     * An event's attributes/tiers/promos were edited. The reactivity service routes by the
     * event's current status: DRAFT → re-score (only if already scored), LIVE → re-forecast.
     */
    public record EventMutated(UUID eventId) {}

    /** A draft was published — triggers a baseline re-forecast IF the draft had a prediction. */
    public record EventPublished(UUID eventId) {}
}
