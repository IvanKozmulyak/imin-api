package com.imin.iminapi.controller.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
    @Schema(allowableValues = {"paid", "partially_refunded", "refunded", "disputed"})
    String status,        // paid | partially_refunded | refunded | disputed
    Instant createdAt,
    List<TicketRow> tickets,
    @Schema(description = "The governing chargeback on this order, null when there is none.")
    DisputeRow dispute
) {
    public record TicketRow(
        UUID id,
        String tierName,
        int priceMinor,
        String state
    ) {}

    /**
     * The chargeback governing the row. {@code status} is the dispute's own lifecycle,
     * not the order's: a LOST dispute still renders the row as {@code disputed} and carries
     * {@code "lost"} here, which is what lets the dashboard say "chargeback lost" rather
     * than inventing a second row status.
     */
    @Schema(name = "OrderDisputeRow")
    public record DisputeRow(
        @Schema(allowableValues = {"open", "won", "lost", "withdrawn_reinstated"})
        String status,
        long amountMinor,
        String currency,
        Instant openedAt
    ) {}
}
