package com.imin.iminapi.cancellation.dto;

import java.util.List;

/**
 * What cancelling the event would do now, over its orders in the running Stripe mode.
 * {@code paidToRefund + alreadyRefunded + disputed + free == orderCount}.
 *
 * @param paidToRefund        paid, no OPEN/LOST dispute, something still owed
 * @param alreadyRefunded     paid, no dispute, fully refunded or being refunded
 * @param disputed            an OPEN or LOST dispute; the bank decides
 * @param free                total 0
 * @param checkoutsInProgress HELD reservations on the event's tiers
 * @param totals              one entry per currency over {@code paidToRefund}
 */
public record EventCancellationPreviewResponse(
        long orderCount,
        long paidToRefund,
        long alreadyRefunded,
        long disputed,
        long free,
        long checkoutsInProgress,
        List<CurrencyTotal> totals) {

    public EventCancellationPreviewResponse {
        totals = List.copyOf(totals);
    }

    /** Refund owed in one currency, split into the booking fee imin returns and the ticket price. */
    public record CurrencyTotal(String currency, long refundMinor, long bookingFeeMinor, long ticketPriceMinor) {}
}
