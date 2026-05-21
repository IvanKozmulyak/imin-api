package com.imin.iminapi.model;

/**
 * Lifecycle of a {@link TicketReservation}. CONFIRMED and RELEASED are
 * terminal — the sweeper, webhook handlers, and {@code InventoryService}
 * all gate transitions on the current status so a replay never double-acts.
 */
public enum ReservationStatus {
    /** Active hold against tier inventory. Counts toward {@code TicketTier.reserved}. */
    HELD,
    /** Payment succeeded; the hold became a sale and was rolled into {@code TicketTier.sold}. */
    CONFIRMED,
    /** Hold released back to the pool (abandoned, failed, expired, or rolled back). */
    RELEASED
}
