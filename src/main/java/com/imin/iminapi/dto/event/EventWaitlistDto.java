package com.imin.iminapi.dto.event;

import java.time.Instant;

/**
 * {@code GET /api/v1/events/{id}/waitlist}: the event's notify-me list, counts only.
 *
 * @param waiting   notify subscriptions still owed the next "tickets available" email
 * @param lastEmail the latest release that emailed anyone; null when none is recorded
 */
public record EventWaitlistDto(long waiting, LastEmail lastEmail) {

    /**
     * @param sentAt    when the release's last email was accepted
     * @param sentCount emails accepted during the whole release; suppressed and ticket-holder skips excluded
     */
    public record LastEmail(Instant sentAt, int sentCount) {}
}
