package com.imin.iminapi.refund.dto;

import com.imin.iminapi.refund.Refund;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row in the EventDetailPage "Refund history" tab. Mirrors
 * {@link RefundResponse} but adds the order's short code + buyer email so the
 * dashboard does not have to fan out per-order to render the table.
 */
public record EventRefundRowResponse(
    UUID id,
    UUID orderId,
    String orderShortCode,
    String orderEmail,
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
    public static EventRefundRowResponse from(Refund r, String orderEmail, List<UUID> ticketIds) {
        return new EventRefundRowResponse(
            r.getId(),
            r.getOrderId(),
            r.getOrderId().toString().substring(0, 8),
            orderEmail,
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
