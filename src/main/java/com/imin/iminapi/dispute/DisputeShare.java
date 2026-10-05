package com.imin.iminapi.dispute;

/**
 * What a chargeback takes off one order, in minor units: {@code grossWithheldMinor} comes off
 * gross-based figures, {@code organizerShareMinor} off the organizer's net (booking fee excluded).
 */
public record DisputeShare(long grossWithheldMinor, long feeShareMinor, long organizerShareMinor) {

    /**
     * What a chargeback takes off this order: the gross part caps at what was not refunded, and
     * the organizer's part leaves imin's booking fee with imin.
     */
    public static DisputeShare of(long totalMinor, long feeMinor, long refundedMinor,
                                  long feeRefundedMinor, long disputedMinor) {
        long remainingGross = Math.max(0L, totalMinor - refundedMinor);
        long remainingFee = Math.max(0L, feeMinor - feeRefundedMinor);
        long gross = Math.min(Math.max(0L, disputedMinor), remainingGross);
        // Same rounding as RefundService.computeAppFeeRefundMinor.
        long fee = totalMinor <= 0 ? 0L
                : Math.min(remainingFee, Math.round((double) feeMinor * gross / totalMinor));
        long share = Math.max(0L, Math.min(gross - fee, remainingGross - remainingFee));
        return new DisputeShare(gross, fee, share);
    }

    /** A dispute with no order to split against: the whole amount counts, the conservative side. */
    public static DisputeShare unattributed(long disputedMinor) {
        return new DisputeShare(disputedMinor, 0L, disputedMinor);
    }
}
