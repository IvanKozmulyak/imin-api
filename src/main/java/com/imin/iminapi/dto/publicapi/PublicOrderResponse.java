package com.imin.iminapi.dto.publicapi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public read-side of a buyer's order, surfaced by
 * {@code GET /api/v1/public/orders/{token}}.
 *
 * <p>Tokenized; the buyer-id and the database UUIDs are never exposed.
 */
public record PublicOrderResponse(
        String token,
        String email,
        long totalMinor,
        String currency,
        String paymentMethod,
        Instant createdAt,
        Event event,
        List<Ticket> tickets) {

    /**
     * {@code eventId} is the public event UUID — the same one already exposed by
     * {@code GET /api/v1/public/events/{id}} — so the confirmation page can link
     * back to the event and build a calendar entry. {@code metaPixelId} mirrors
     * the event-page resolution (event-scoped override → org-wide → active-only)
     * and drives the browser Purchase pixel; null when the organizer has none.
     */
    public record Event(UUID eventId, String name, String slug, Instant startsAt, Instant endsAt,
                        String timezone,
                        String venueName, String venueStreet, String venueCity,
                        String venuePostalCode, String venueCountry,
                        String posterUrl, String metaPixelId) {}

    /** {@code qrPayload} is the signed {@code imin1.<token>.<hmac>} string, so the
     *  order page can pre-render every ticket's QR without an N+1 per-ticket fetch. */
    public record Ticket(String token, String tierName, String state, String qrPayload) {}
}
