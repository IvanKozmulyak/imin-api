package com.imin.iminapi.refund;

public enum RefundStatus {
    REQUESTED, PENDING, SUCCEEDED, FAILED, CANCELED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }

    /**
     * Map Stripe's lowercase status string to our enum. Unknown values default
     * to PENDING — Stripe occasionally adds new transient states and we'd rather
     * wait for an authoritative succeeded/failed than misclassify.
     */
    public static RefundStatus fromStripe(String stripeStatus) {
        if (stripeStatus == null) return PENDING;
        return switch (stripeStatus) {
            case "pending" -> PENDING;
            case "succeeded" -> SUCCEEDED;
            case "failed" -> FAILED;
            case "canceled" -> CANCELED;
            default -> PENDING;
        };
    }
}
