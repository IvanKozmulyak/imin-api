package com.imin.iminapi.cancellation;

/**
 * What a cancellation still owes one order: everything paid that is not already refunded or being
 * refunded, split into the booking fee imin returns and the ticket price the organizer bears.
 *
 * <p>The fee part is the fee not yet refunded, never a recomputed proportion: this is the order's last
 * refund, and the remainder is what Stripe's proportional {@code refund_application_fee} lands on.
 */
public record CancellationAmounts(long refundMinor, long feeMinor, long ticketPriceMinor) {

    /**
     * @param totalMinor             {@code orders.total_minor}, ticket price plus booking fee as paid
     * @param feeMinor               {@code orders.application_fee_minor}
     * @param activeRefundedMinor    REQUESTED + PENDING + SUCCEEDED refunds of the order
     * @param activeFeeRefundedMinor their application-fee refunds
     */
    public static CancellationAmounts of(long totalMinor, long feeMinor,
                                         long activeRefundedMinor, long activeFeeRefundedMinor) {
        long refund = Math.max(0, totalMinor - activeRefundedMinor);
        long fee = Math.max(0, Math.min(feeMinor - activeFeeRefundedMinor, refund));
        return new CancellationAmounts(refund, fee, refund - fee);
    }
}
