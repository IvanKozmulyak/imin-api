package com.imin.iminapi.settlement;

/**
 * Status of a mirrored Stripe transfer/payout in the settlements read-model.
 *
 * <p>Stored as VARCHAR (@Enumerated(EnumType.STRING)); the wire/DB form is the
 * lowercase-with-underscore Stripe vocabulary ({@code in_transit}, not
 * {@code IN_TRANSIT}) so it matches the FE Payout status union directly. Use
 * {@link #toWire()} when emitting JSON and {@link #fromStripe(String)} when
 * mapping a Stripe object's status string.
 */
public enum SettlementStatus {
    PENDING,
    IN_TRANSIT,
    PAID,
    FAILED,
    REVERSED;

    /** Stable wire/JSON form: lowercase with underscores (e.g. {@code in_transit}). */
    public String toWire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Map a Stripe payout/transfer status string to our enum. Unknown values
     * default to PENDING so a new transient Stripe state is treated as
     * not-yet-settled rather than silently dropped.
     */
    public static SettlementStatus fromStripe(String stripeStatus) {
        if (stripeStatus == null) return PENDING;
        return switch (stripeStatus) {
            case "pending" -> PENDING;
            case "in_transit" -> IN_TRANSIT;
            case "paid" -> PAID;
            case "failed", "canceled" -> FAILED;
            case "reversed" -> REVERSED;
            default -> PENDING;
        };
    }
}
