package com.imin.iminapi.dto.publicapi;

/**
 * Response of {@code POST /api/v1/public/events/{id}/notify}.
 *
 * Idempotent: duplicate submissions (same event_id + email, any case) return
 * {@code subscribed=true} the same as a first-time insert.
 */
public record NotifySubscriptionResponse(boolean subscribed) {
    public static NotifySubscriptionResponse ok() {
        return new NotifySubscriptionResponse(true);
    }
}
