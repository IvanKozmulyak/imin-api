package com.imin.iminapi.dispute;

import java.util.Locale;

/**
 * Lifecycle state of a chargeback in the {@code disputes} registry.
 *
 * <p>Stored in its lowercase wire form via {@link DisputeStatusConverter}, matching
 * {@code SettlementStatus} / {@code PayoutRunStatus}. Only {@link #OPEN} blocks an org's
 * payouts; {@link #LOST} stops blocking but permanently reduces the event's payable net,
 * while {@link #WON} and {@link #WITHDRAWN_REINSTATED} give the money back by construction
 * (they are excluded from the reduction sum).
 */
public enum DisputeStatus {
    /** Funds are at risk and the outcome is unknown — payouts for the org are frozen. */
    OPEN,
    /** Stripe found for the organizer; the money stays. */
    WON,
    /** Stripe found for the cardholder; the face value is gone for good. */
    LOST,
    /** The cardholder withdrew, or Stripe reinstated the funds without a formal win. */
    WITHDRAWN_REINSTATED;

    /** Stable wire/DB form: lowercase with underscores (e.g. {@code withdrawn_reinstated}). */
    public String toWire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Map a Stripe {@code dispute.status} to our enum. {@code won} and {@code lost} are the two
     * terminal answers; {@code warning_closed} is a third — an inquiry that ended with no money
     * moved, so the funds were never at risk and the tickets go back. Everything else
     * ({@code needs_response}, {@code under_review}, the other {@code warning_*} values, and any
     * status Stripe adds later) is OPEN: the conservative side, because OPEN freezes payouts
     * rather than releasing money on a state we do not understand.
     */
    public static DisputeStatus fromStripe(String stripeStatus) {
        if (stripeStatus == null) return OPEN;
        return switch (stripeStatus.toLowerCase(Locale.ROOT)) {
            case "won" -> WON;
            case "lost" -> LOST;
            case "warning_closed" -> WITHDRAWN_REINSTATED;
            default -> OPEN;
        };
    }
}
