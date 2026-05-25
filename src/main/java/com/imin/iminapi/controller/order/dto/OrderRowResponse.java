package com.imin.iminapi.controller.order.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row in the EventDetailPage Orders tab. Includes per-ticket details so
 * the dashboard can render the refund modal's per-ticket checkbox list without
 * a second fetch.
 */
public record OrderRowResponse(
    UUID id,
    String shortCode,
    String email,
    long totalMinor,
    String currency,
    int ticketCount,
    int refundedTicketCount,
    String status,        // paid | partially_refunded | refunded
    Instant createdAt,
    List<TicketRow> tickets
) {
    public record TicketRow(
        UUID id,
        String tierName,
        int priceMinor,
        String state
    ) {}
}
