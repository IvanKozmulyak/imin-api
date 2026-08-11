package com.imin.iminapi.refund.dto;

import java.time.Instant;
import java.util.UUID;

public record RefundRequestSummaryResponse(
    UUID id,
    String reference,
    UUID orderId,
    UUID eventId,
    String eventName,
    String buyerEmail,
    String status,
    String reason,
    Instant createdAt,
    Instant decidedAt,
    int ticketCount,
    long estimatedRefundMinor,
    String currency,
    UUID refundId,
    String refundStatus
) {}
