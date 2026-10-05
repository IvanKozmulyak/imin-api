package com.imin.iminapi.settlement;

import com.imin.iminapi.dispute.DisputeShare;
import com.imin.iminapi.model.Order;

/**
 * Converts one order's presentment figures into what Stripe settled for it, at that order's own
 * ratio: {@code gross(x) = x · Gs / G} and {@code fee(y) = y · Fs / F}, half-up. The same proportional
 * rule Stripe uses for {@code reverse_transfer} and {@code refund_application_fee}; the identity
 * whenever the order was not converted.
 */
public record SettlementRate(long totalMinor, long feeMinor, long settledGrossMinor, long settledFeeMinor) {

    /** The order's rate; throws when the payout sweep has not stamped it yet. */
    public static SettlementRate of(Order order) {
        if (order.getSettlementCurrency() == null
                || order.getSettlementGrossMinor() == null || order.getSettlementFeeMinor() == null) {
            throw new IllegalStateException("order " + order.getId() + " has no settlement stamp");
        }
        return new SettlementRate(order.getTotalMinor(), order.getApplicationFeeMinor(),
                order.getSettlementGrossMinor(), order.getSettlementFeeMinor());
    }

    /** A presentment gross amount in settlement minor units. */
    public long gross(long presentmentMinor) {
        return scale(presentmentMinor, settledGrossMinor, totalMinor);
    }

    /** A presentment fee amount in settlement minor units. */
    public long fee(long presentmentMinor) {
        return scale(presentmentMinor, settledFeeMinor, feeMinor);
    }

    /** The organizer's settled stake after refunds: what is left of the transfer less the fee still kept. */
    public long stake(long refundedMinor, long feeRefundedMinor) {
        long grossLeft = Math.max(0L, settledGrossMinor - gross(refundedMinor));
        long feeLeft = Math.max(0L, settledFeeMinor - fee(feeRefundedMinor));
        return Math.max(0L, grossLeft - feeLeft);
    }

    /** A presentment dispute split converted, capped at the settled stake. */
    public long organizerShare(DisputeShare share, long refundedMinor, long feeRefundedMinor) {
        long converted = gross(share.grossWithheldMinor()) - fee(share.feeShareMinor());
        return Math.max(0L, Math.min(converted, stake(refundedMinor, feeRefundedMinor)));
    }

    /** Half-up {@code x · num / den}, loud on overflow; 0 for a non-positive amount or denominator. */
    static long scale(long x, long num, long den) {
        if (den <= 0L || x <= 0L) return 0L;
        if (num == den) return x;
        return Math.floorDiv(Math.addExact(Math.multiplyExact(Math.multiplyExact(2L, x), num), den),
                Math.multiplyExact(2L, den));
    }
}
