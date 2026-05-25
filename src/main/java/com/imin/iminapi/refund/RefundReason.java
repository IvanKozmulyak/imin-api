package com.imin.iminapi.refund;

public enum RefundReason {
    REQUESTED_BY_CUSTOMER, DUPLICATE, FRAUDULENT, OTHER;

    /**
     * The lowercase string Stripe accepts on RefundCreateParams.reason.
     * OTHER maps to null because Stripe's enum does not include it — we just
     * omit the field and Stripe records the refund with no reason set.
     */
    public String toStripe() {
        return switch (this) {
            case REQUESTED_BY_CUSTOMER -> "requested_by_customer";
            case DUPLICATE -> "duplicate";
            case FRAUDULENT -> "fraudulent";
            case OTHER -> null;
        };
    }

    /** Stable wire format used in API responses (lowercase enum name). */
    public String toWire() {
        return name().toLowerCase();
    }
}
