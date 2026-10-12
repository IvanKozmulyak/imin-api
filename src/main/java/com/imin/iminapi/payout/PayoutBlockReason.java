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

    /** Stripe refused the key as reused with other params: a payout may exist. Needs a human, no organizer notice. */
    public static final String IDEMPOTENCY_MISMATCH = "PAYOUT_IDEMPOTENCY_MISMATCH";

    /** Stripe's payout list could not be read for over a week, so the outcome stays unknown. Needs a human. */
    public static final String OUTCOME_UNKNOWN = "PAYOUT_OUTCOME_UNKNOWN";

    /** Two or more unrecorded Stripe payouts match the run, so which one it made cannot be told. Needs a human. */
    public static final String AMBIGUOUS_MATCH = "PAYOUT_AMBIGUOUS_MATCH";

    /** FAILED reason when Stripe's payout list shows the run never created a payout; the next attempt is fresh. */
    public static final String NOT_CREATED = "not_created";

    private PayoutBlockReason() {}
}
