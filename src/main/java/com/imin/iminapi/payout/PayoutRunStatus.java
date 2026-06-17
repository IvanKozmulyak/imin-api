package com.imin.iminapi.payout;

/**
 * Lifecycle of an imin-triggered payout run (Track B trigger ledger).
 *
 * <p>Stored as a lowercase VARCHAR via {@link PayoutRunStatusConverter} so the DB
 * value matches the {@code V45__payout_runs.sql} literals
 * ({@code planned}/{@code submitted}/{@code paid}/{@code failed}) rather than the
 * uppercase constant name — mirroring the Track A {@code SettlementStatus}
 * approach.
 *
 * <p>Transitions: {@code PLANNED -> SUBMITTED -> PAID | FAILED}. A row is written
 * {@code PLANNED} BEFORE the Stripe {@code Payout.create} call (the pre-call
 * double-pay guard), moves to {@code SUBMITTED} once Stripe accepts (carrying the
 * {@code po_} id), and is reconciled to {@code PAID}/{@code FAILED} by the
 * {@code payout.paid}/{@code payout.failed} webhooks. A new {@code attempt} (hence
 * a fresh idempotency key) is used ONLY after a {@code FAILED} run.
 */
public enum PayoutRunStatus {
    PLANNED,
    SUBMITTED,
    PAID,
    FAILED;

    /** Stable wire/DB form: lowercase (e.g. {@code submitted}). */
    public String toWire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
