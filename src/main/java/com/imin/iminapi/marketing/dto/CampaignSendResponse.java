package com.imin.iminapi.marketing.dto;

import java.time.Instant;

/**
 * Outcome of {@code POST /campaigns/{id}/send}: the stored send time, or {@code armed} with no time for an
 * audience-plan slump arm that waits for Momentum. {@code secondsUntilScheduled} counts from the server's clock
 * at the moment of the send (0 when armed); {@code quietUntil} is set only when the stored time falls in the
 * org's quiet hours (the next 09:00 org time), and {@code timeZone} is the org zone it was judged in.
 */
public record CampaignSendResponse(Instant scheduledAt, boolean armed, long secondsUntilScheduled,
                                   Instant quietUntil, String timeZone) {

    /** The armed response: no stored time, nothing to count down to. */
    public CampaignSendResponse(Instant scheduledAt, boolean armed) {
        this(scheduledAt, armed, 0L, null, null);
    }
}
