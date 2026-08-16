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
        List<Ticket> tickets,
        List<Line> lines,
        long subtotalMinor,
        long discountMinor,
        long feeMinor) {

    /**
     * One receipt row: tickets grouped by {@code (tier_id, price_minor)}.
     *
     * <p>Grouped by price as well as tier because a tier's price can move
     * between purchases; two rows at different prices are two rows, not one
     * row with a fabricated average.
     *
     * <p><b>There is no refund-protection line.</b> The design's receipt shows
     * one; imin does not sell that product, and inventing a line item for it
     * would put a charge on a receipt that was never made.
     */
    public record Line(String tierName, int quantity, long unitPriceMinor, long amountMinor) {}

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

    /**
     * {@code qrPayload} is the signed {@code imin1.<token>.<hmac>} string, so the
     * order page can pre-render every ticket's QR without an N+1 per-ticket fetch.
     *
     * <p>{@code wallet} is here for the same reason and closes the same N+1.
     * {@code imin-public/components/buyer/order-view.tsx:53-63} currently fetches
     * a <i>whole extra ticket</i> through a {@code <Suspense>} boundary purely to
     * learn one boolean, because the order response did not carry it. It is
     * resolved per ticket, not once per order: an order with one refunded ticket
     * and three live ones is four different answers.
     *
     * <p>There is no {@code walletAvailable} on this record and there never was —
     * nothing has ever read one here, so the Apple-only flag has no installed
     * reader to preserve and the new block is the whole contract.
     */
    public record Ticket(String token, String tierName, String state, String qrPayload,
                         TicketWallets wallet) {}
}
