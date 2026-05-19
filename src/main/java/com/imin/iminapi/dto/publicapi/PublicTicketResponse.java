package com.imin.iminapi.dto.publicapi;

import java.time.Instant;

/**
 * Public read-side of a single ticket, surfaced by
 * {@code GET /api/v1/public/tickets/{token}}. Drives the web-ticket UI
 * (state badge, event headline, tier label, scan code).
 */
public record PublicTicketResponse(
        String token,
        String state,
        String tierName,
        Event event,
        Order order) {

    public record Event(String name, String slug, Instant startsAt, String timezone,
                        String venueName, String venueCity) {}

    /** Sibling info so the ticket page can link "back to order" without a second call. */
    public record Order(String token, String email) {}
}
