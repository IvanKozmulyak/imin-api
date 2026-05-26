package com.imin.iminapi.refund.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundRequestDetailResponse(
    UUID id,
    UUID orderId,
    UUID eventId,
    String eventName,
    String buyerEmail,
    String buyerPhone,
    String status,
    String reason,
    String explanation,
    String decisionNote,
    Instant createdAt,
    Instant decidedAt,
    List<TicketLine> tickets,
    ProposedRefundResponse proposedRefund,
    UUID refundId,
    String refundStatus
) {
    public record TicketLine(UUID id, String tierName, long faceMinor, String state) {}
}
