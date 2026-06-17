package com.imin.iminapi.settlement;

/**
 * Which Stripe object family a {@link Settlement} row mirrors.
 *
 * <p>Stored as VARCHAR (@Enumerated(EnumType.STRING)); the wire/DB form is the
 * lowercase Stripe object name ({@code transfer} / {@code payout}).
 */
public enum SettlementObjectType {
    TRANSFER,
    PAYOUT;

    /** Stable wire/DB form: lowercase Stripe object name. */
    public String toWire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
