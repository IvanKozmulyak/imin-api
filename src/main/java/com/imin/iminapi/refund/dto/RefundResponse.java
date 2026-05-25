package com.imin.iminapi.refund.dto;

import com.imin.iminapi.refund.Refund;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundResponse(
    UUID id,
    UUID orderId,
    String stripeRefundId,
    long amountMinor,
    String currency,
    long applicationFeeRefundMinor,
    String status,
    String reason,
    List<UUID> ticketIds,
    String failureMessage,
    Instant createdAt
) {
    public static RefundResponse from(Refund r, List<UUID> ticketIds) {
        return new RefundResponse(
            r.getId(),
            r.getOrderId(),
            r.getStripeRefundId(),
            r.getAmountMinor(),
            r.getCurrency(),
            r.getApplicationFeeRefundMinor(),
            r.getStatus().name().toLowerCase(),
            r.getReason().toWire(),
            ticketIds,
            r.getFailureMessage(),
            r.getCreatedAt()
        );
    }
}
