package com.imin.iminapi.refund.dto;

import com.imin.iminapi.refund.RefundRequestReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Form context behind a refund link.
 *
 * <p>{@code openRequestReference} is the short code of the refund request already
 * open on this order, or null when there is none — which is the normal case, since a
 * token is issued before anything is submitted. It is here because a buyer who
 * re-requests a link while a request is pending would otherwise fill the form only to
 * be 409'd; with the reference in hand the FE can say "REQ-8K2M-26 is already with the
 * organizer" instead. The submit response is where a fresh reference comes from.
 *
 * <p>{@code nonRefundableTicketCount} is the number of tickets on the order that the
 * eligibility filter left OUT of {@code tickets} — precisely
 * {@code (all tickets on the order) - tickets.size()}. It exists so the buyer can be
 * told why the list in front of them is shorter than the order they remember, and why
 * {@code estimatedRefundMinor} is correspondingly smaller. It is a reporting field
 * only: nothing downstream reads it, and it can never widen or narrow what is
 * refundable. Because it is derived by subtraction from the very list it sits beside,
 * it cannot drift out of agreement with that list — a banner that contradicted the
 * rows under it would be worse than no banner. Zero on a fully refundable order.
 */
public record PublicRefundFormResponse(
    UUID orderId,
    EventSummary event,
    List<TicketLine> tickets,
    int nonRefundableTicketCount,
    long estimatedRefundMinor,
    String currency,
    List<String> reasons,
    String openRequestReference
) {
    public record EventSummary(String name, Instant startsAt, String timezone, String venueName, String currency) {}
    public record TicketLine(UUID id, String tierName, long faceMinor) {}

    public static List<String> defaultReasons() {
        return List.of(
            RefundRequestReason.CANT_ATTEND.toWire(),
            RefundRequestReason.EVENT_CHANGED.toWire(),
            RefundRequestReason.DUPLICATE_PURCHASE.toWire(),
            RefundRequestReason.NOT_AS_DESCRIBED.toWire(),
            RefundRequestReason.OTHER.toWire()
        );
    }
}
