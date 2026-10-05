package com.imin.iminapi.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Top-bar pulse: is anything on sale now, and the newest order that still holds a ticket. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DashboardPulseResponse(
        @Schema(description = "True when onSaleCount > 0.")
        boolean onSale,
        @Schema(description = "LIVE, not deleted, future events whose own sale window is open now."
                + " Tier-level closures are not considered.")
        int onSaleCount,
        @Schema(types = {"object", "null"}, description = "Newest order in scope with at least one non-refunded ticket,"
                + " test mode included. Null when there is none.")
        LastSale lastSale) {

    public record LastSale(
            @Schema(description = "Order creation instant.")
            Instant at,
            UUID eventId,
            String eventName,
            @Schema(description = "Distinct tier names of the order's non-refunded tickets, in ticket order.")
            List<String> tierNames,
            @Schema(description = "Non-refunded tickets on the order.")
            int ticketCount) {}
}
