package com.imin.iminapi.model;

/**
 * Ticket lifecycle states. Wire format is the lowercase name as stored in
 * {@code tickets.state}. {@code 'pre'} is legacy (free-checkout default before
 * V26); we translate it as {@link #ISSUED} at the API boundary rather than
 * doing a destructive backfill.
 *
 * <p>{@code 'checkedIn'} is accepted as an inbound synonym for
 * {@link #REDEEMED} because the {@code imin-public} ticket page has been
 * labeling that state since before this enum existed.
 */
public enum TicketState {
    ISSUED,
    REDEEMED,
    REVOKED;

    public String wire() {
        return name().toLowerCase();
    }

    public static TicketState fromWire(String wire) {
        if (wire == null) return ISSUED;
        return switch (wire) {
            case "pre", "issued" -> ISSUED;
            case "redeemed", "checkedIn" -> REDEEMED;
            case "revoked" -> REVOKED;
            default -> throw new IllegalArgumentException("Unknown ticket state: " + wire);
        };
    }
}
