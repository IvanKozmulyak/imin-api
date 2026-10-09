package com.imin.iminapi.refund.dto;

import java.util.List;
import java.util.UUID;

/**
 * Refund-all plan for an event: every order and ticket {@code POST /orders/{id}/refund} would
 * accept right now, with the amount it would charge. {@code currency} is the planned orders' one
 * currency (the event's when nothing is planned); orders in several currencies are a 409.
 */
public record EventRefundPlanResponse(
        String currency,
        long totalAmountMinor,
        int ticketCount,
        List<EventRefundPlanOrder> orders,
        EventRefundPlanSkipped skipped) {

    public EventRefundPlanResponse {
        orders = List.copyOf(orders);
    }

    public record EventRefundPlanOrder(UUID orderId, String shortCode, List<UUID> ticketIds, long amountMinor,
                                       String currency) {
        public EventRefundPlanOrder {
            ticketIds = List.copyOf(ticketIds);
        }
    }

    /** Disputed orders left out whole, and redeemed tickets left out of otherwise planned orders. */
    public record EventRefundPlanSkipped(int disputedOrders, int redeemedTickets) {}
}
