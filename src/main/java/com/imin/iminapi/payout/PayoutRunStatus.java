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
 * <p>Transitions: {@code PLANNED -> SUBMITTED -> PAID | FAILED}, plus the
 * {@code PLANNED -> RETRYING -> SUBMITTED} transport-failure loop. A row is written
 * {@code PLANNED} BEFORE the Stripe {@code Payout.create} call (the pre-call
 * double-pay guard), moves to {@code SUBMITTED} once Stripe accepts (carrying the
 * {@code po_} id), and is reconciled to {@code PAID}/{@code FAILED} by the
 * {@code payout.paid}/{@code payout.failed} webhooks. A new {@code attempt} (hence
 * a fresh idempotency key) is used ONLY after a {@code FAILED} run — NEVER after
 * {@code RETRYING}.
 *
 * <p><b>{@code RETRYING}</b> means "we do NOT know whether Stripe minted a payout":
 * a connection/read timeout, a rate limit or a 5xx. The run keeps its
 * {@code attempt} and its {@code idempotency_key} so the next tick REPLAYS the same
 * Stripe request — Stripe's idempotency then returns the ORIGINAL {@code po_}
 * instead of moving money a second time. Only a definitive 4xx rejection (the
 * request reached Stripe and was refused, so no {@code po_} exists) may advance the
 * attempt counter via {@code FAILED}. Bumping the attempt on a timeout is a real
 * double-pay: the fresh key mints a SECOND bank payout for the same event.
 */
public enum PayoutRunStatus {
    PLANNED,
    SUBMITTED,
    /** Transport-level failure — outcome UNKNOWN. Same key is replayed; attempt NOT bumped. */
    RETRYING,
    PAID,
    FAILED;

    /** Stable wire/DB form: lowercase (e.g. {@code submitted}). */
    public String toWire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
