package com.imin.iminapi.audience.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the platform holds about one data subject inside one org, beyond
 * the membership row itself — Art.15(1)(b)-(h): the actual records, not a
 * summary of them.
 *
 * <p>Before this, a DSAR export answered with the audience projection alone:
 * no orders, no tickets, no browsing beacons, no record of the hashed address
 * that had been sent to Meta. Those are the rows a data subject is most likely
 * to be asking about.
 *
 * <p>Ticket tokens are <b>hashed</b>, never returned raw. A ticket token is a
 * bearer credential — anyone holding it can load the ticket and add it to a
 * wallet — so an export that printed it would hand a copy of the ticket to
 * whoever the export is later forwarded to. The hash still lets the subject
 * match an export line against a ticket they hold.
 */
public record DsarRecords(
        List<OrderRecord> orders,
        List<TicketRecord> tickets,
        List<FunnelRecord> funnelEvents,
        List<MetaCapiRecord> metaCapiEvents,
        List<NotifySubscriptionRecord> notifySubscriptions
) {
    public record OrderRecord(
            UUID id,
            UUID eventId,
            String eventName,
            String email,
            long totalMinor,
            long bookingFeeMinor,
            String currency,
            String paymentMethod,
            Instant createdAt) {}

    public record TicketRecord(
            UUID id,
            UUID orderId,
            String tokenSha256,
            String tierName,
            String state,
            Instant redeemedAt,
            Instant createdAt) {}

    /**
     * A /track beacon row, reached through {@code orders.anon_id} — the join
     * that makes these rows personal data rather than audience measurement.
     */
    public record FunnelRecord(
            UUID eventId,
            String stage,
            String anonId,
            String utmSource,
            String client,
            Instant at) {}

    /** A server-side Meta Conversions API send: what left, when, for which order. */
    public record MetaCapiRecord(
            UUID orderId,
            String eventName,
            String emailSha256,
            String orderToken,
            String status,
            Instant sentAt,
            Instant createdAt) {}

    /** A "tell me when tickets drop" registration. */
    public record NotifySubscriptionRecord(
            UUID id,
            UUID eventId,
            String email,
            Instant createdAt) {}
}
