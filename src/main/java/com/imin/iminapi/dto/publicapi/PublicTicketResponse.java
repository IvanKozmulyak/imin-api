package com.imin.iminapi.dto.publicapi;

import java.time.Instant;

/**
 * Public read-side of a single ticket, surfaced by
 * {@code GET /api/v1/public/tickets/{token}}. Drives the web-ticket UI
 * (state badge, event headline, tier label, QR code, wallet button).
 *
 * <p>{@code qrPayload} is the signed {@code imin1.<token>.<hmac>} string —
 * useful for hand-rolled scanner integrations. {@code qrUrl} is the
 * server-rendered PNG that the buyer's web ticket page can simply
 * {@code <img src>} onto. {@code walletAvailable} gates the "Add to Apple
 * Wallet" CTA — false when the server isn't configured for Wallet pass
 * signing so the FE can suppress a button that would 503.
 */
public record PublicTicketResponse(
        String token,
        String state,
        String tierName,
        String qrPayload,
        String qrUrl,
        boolean walletAvailable,
        Event event,
        Order order) {

    public record Event(String name, String slug, Instant startsAt, String timezone,
                        String venueName, String venueStreet, String venueCity,
                        String venuePostalCode, String venueCountry) {}

    /** Sibling info so the ticket page can link "back to order" without a second call. */
    public record Order(String token, String email) {}
}
