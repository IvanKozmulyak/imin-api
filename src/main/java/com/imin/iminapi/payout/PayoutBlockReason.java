package com.imin.iminapi.payout;

/**
 * The {@code failure_reason} values imin itself writes on a {@link PayoutRunStatus#BLOCKED}
 * run. Every other BLOCKED reason is a Stripe failure code copied off the last failed attempt.
 *
 * <p>The distinction is load-bearing: {@link #NO_BANK_ACCOUNT} is the ONLY self-healing block
 * (the organizer attaches a bank account and the next sweep pays out), so it must never park
 * the event permanently the way an attempt-cap block does.
 */
public final class PayoutBlockReason {

    /** The connected account has no external bank account to pay into. Self-healing. */
    public static final String NO_BANK_ACCOUNT = "NO_BANK_ACCOUNT";

    /** Attempt cap reached and the last attempt reported no Stripe code. Needs a human. */
    public static final String ATTEMPT_LIMIT = "PAYOUT_ATTEMPT_LIMIT";

    private PayoutBlockReason() {}
}
