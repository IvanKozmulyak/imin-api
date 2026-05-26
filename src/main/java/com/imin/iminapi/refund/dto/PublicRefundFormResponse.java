package com.imin.iminapi.refund.dto;

import com.imin.iminapi.refund.RefundRequestReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublicRefundFormResponse(
    UUID orderId,
    EventSummary event,
    List<TicketLine> tickets,
    long estimatedRefundMinor,
    String currency,
    List<String> reasons
) {
    public record EventSummary(String name, Instant startsAt, String venueName, String currency) {}
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
