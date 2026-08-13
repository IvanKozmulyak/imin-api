package com.imin.iminapi.buyer.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /buyer/orders} — a buyer's orders across every organizer
 * (epic §3.4).
 *
 * <h2>This body is a bundle of bearer credentials</h2>
 *
 * <p>Each {@link Item#orderToken} opens an order page and its tickets with no
 * further authentication. That is what makes the verified-address gate on the
 * query load-bearing, and it is why the endpoint is {@code no-store},
 * {@code hasRole("BUYER")}, cookie-authenticated, and must never be logged with
 * its body.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <ul>
 *   <li><b>{@code qrPayload}.</b> Those are signed per ticket at read time
 *       ({@code PublicOrderController:91}) and belong on the page that displays
 *       them, not in a list of everything the buyer has ever bought.</li>
 *   <li><b>An upcoming/past split.</b> Derived client-side from
 *       {@code event.startsAt}; a server-side split is a second opinion the
 *       frontend would eventually disagree with.</li>
 *   <li><b>A recency window.</b> {@code /orders/recover} caps at 90 days because
 *       it is an anti-abuse measure on an <i>unauthenticated</i> endpoint. An
 *       authenticated account view that hid a buyer's older tickets would be a
 *       bug, not a control.</li>
 * </ul>
 */
public record BuyerOrdersResponse(List<Item> items, String nextCursor) {

    /**
     * One order. {@code ticketCount} is every ticket on it, including refunded
     * and revoked ones — {@link States} is how the UI tells them apart, and
     * hiding them would make an order's total look wrong against its tickets.
     */
    public record Item(
            String orderToken,
            Instant createdAt,
            long totalMinor,
            String currency,
            Event event,
            int ticketCount,
            States states,
            /**
             * The most recent refund request on this order, or <b>null</b> when
             * there is none — never the string {@code "NONE"}. The frontend
             * renders no chip for null; a chip reading "none" would announce a
             * non-event on every order the buyer ever placed.
             */
            String refundRequestStatus) {}

    /** Just enough of the event to render a ticket card without a second call. */
    public record Event(
            UUID id,
            String name,
            String slug,
            Instant startsAt,
            Instant endsAt,
            String timezone,
            String venueName,
            String venueCity,
            String posterUrl) {}

    /** Ticket counts by canonical state. Legacy {@code 'pre'} rows count as issued. */
    public record States(int issued, int redeemed, int refunded, int revoked) {}
}
