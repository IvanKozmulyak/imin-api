package com.imin.iminapi.cancellation.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Progress of a cancellation. The {@code orders} buckets sum to {@code orders.total}, which leaves out
 * orders paid in the other Stripe mode.
 *
 * @param overdue        orders not refunded yet although the event was cancelled over 3 days ago
 * @param buyersEmailed  cancellation notices sent
 * @param buyersToEmail  orders whose buyer is to be told (same set as {@code orders.total})
 */
public record EventCancellationResponse(
        UUID eventId,
        Instant cancelledAt,
        Orders orders,
        long overdue,
        long buyersEmailed,
        long buyersToEmail) {

    /**
     * @param refunded         refund settled or started at Stripe
     * @param processing       refund not started yet, or its outcome is being confirmed
     * @param waitingOnDispute an open dispute decides first
     * @param settledByBank    a lost dispute already returned the money
     * @param free             nothing to refund
     * @param needsAttention   imin has to step in
     */
    public record Orders(long total, long refunded, long processing, long waitingOnDispute,
                         long settledByBank, long free, long needsAttention) {}
}
