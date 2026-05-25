package com.imin.iminapi.dto.publicapi;

import java.time.Instant;
import java.util.List;

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

    public record Event(String name, String slug, Instant startsAt, String timezone,
                        String venueName, String venueStreet, String venueCity,
                        String venuePostalCode, String venueCountry) {}

    public record Ticket(String token, String tierName, String state) {}
}
