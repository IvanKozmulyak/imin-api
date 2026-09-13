package com.imin.iminapi.payout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/write access to the {@code payout_runs} trigger ledger. {@code exported =
 * false} keeps Spring Data REST from auto-exposing a public CRUD endpoint — every
 * repo in this codebase sets this.
 *
 * <p>NOTE: {@code status} is persisted via {@link PayoutRunStatusConverter}, so
 * the {@code In(Collection<PayoutRunStatus>)} derived queries bind each enum as a
 * parameter routed through the converter to its lowercase column form — same
 * mechanic as {@code SettlementRepository}'s parameter-bound enum filters.
 */
@RepositoryRestResource(exported = false)
public interface PayoutRunRepository extends JpaRepository<PayoutRun, UUID> {

    /** Any payout row at all — the money-has-moved gate on org deletion. */
    boolean existsByOrgId(UUID orgId);

    /**
     * Insert-or-find key for the per-event payout unit: the deterministic
     * {@code "evt:<eventId>:attempt:<attempt>"} idempotency key. UNIQUE in the DB,
     * so this is how concurrent replicas converge on a single PLANNED row.
     */
    Optional<PayoutRun> findByIdempotencyKey(String idempotencyKey);

    /** Reconciliation match: link a {@code payout.*} webhook's {@code po_} back to its run. */
    Optional<PayoutRun> findByStripePayoutId(String stripePayoutId);

    /**
     * Org-level in-flight double-pay guard (§4.0 HARD RULE): a connected balance is
     * a single shared pool, so AT MOST ONE in-flight payout per account per tick.
     * Bind {@code [PLANNED, SUBMITTED]} — re-checked inside the per-event
     * {@code REQUIRES_NEW} tx BEFORE any balance read or {@code Payout.create}.
     */
    boolean existsByStripeAccountIdAndStatusIn(String stripeAccountId, Collection<PayoutRunStatus> statuses);

    /** Runs for an event, e.g. to compute the next {@code attempt} after a FAILED run. */
    List<PayoutRun> findByEventId(UUID eventId);

    /**
     * The unresolved run to REPLAY for an event, if any. A {@code RETRYING} run is one
     * whose {@code Payout.create} failed at the transport level (timeout / rate limit /
     * 5xx), so Stripe MAY already hold a {@code po_} for its idempotency key. The next
     * tick must reuse that row's {@code attempt} — and therefore its key — so the replay
     * converges on the original payout instead of minting a second one.
     */
    Optional<PayoutRun> findFirstByEventIdAndStatusOrderByAttemptDesc(UUID eventId, PayoutRunStatus status);

    /**
     * Highest {@code attempt} recorded for an event, or {@code 0} when there are no runs.
     * Used to compute the next attempt (a fresh idempotency key after a FAILED run) without
     * loading every row.
     *
     * <p>Deliberately NOT filtered to live runs (V130): counting a test-era attempt only pushes
     * the next idempotency key further from any key Stripe has already seen, which is the safe
     * direction. Filtering could hand a live payout a key a test-era run already used.
     */
    @Query("select coalesce(max(r.attempt), 0) from PayoutRun r where r.eventId = :eventId")
    int maxAttemptByEventId(@Param("eventId") UUID eventId);

    /**
     * Reconciliation sweep: runs left {@code SUBMITTED} since before {@code cutoff}. A
     * SUBMITTED run blocks EVERY event for its org (the org-level in-flight guard), and
     * its only other exit is a {@code payout.*} webhook — which is dark whenever
     * {@code STRIPE_WEBHOOK_SECRET_CONNECT} is unset and which Stripe stops retrying
     * after ~3 days. These are the rows to re-read from Stripe directly.
     */
    List<PayoutRun> findByStatusAndSubmittedAtBefore(PayoutRunStatus status, Instant cutoff);

    /**
     * Retention monitor (plan §7): is there a settled ({@code PAID}) payout run for
     * this event? Used to decide whether an org is still holding un-disbursed funds
     * for an event that is well past its end date and approaching Stripe's retention
     * window.
     */
    boolean existsByEventIdAndStatus(UUID eventId, PayoutRunStatus status);

    /**
     * Fee-retention monitor (§4.4): total minor units imin has triggered as payouts
     * for an event across PAID/SUBMITTED runs. Any sum exceeding the event's
     * {@code gross - fee - refunds} is a bug to alert on. Bind the relevant statuses.
     *
     * <p>LIVE-mode runs only (V130): a test-era run moved nothing out of a live connected
     * balance, so counting it as already triggered would silently shrink the first live
     * payout by an amount the organizer never received.
     */
    @Query("""
            select coalesce(sum(r.amountMinor), 0) from PayoutRun r
             where r.eventId = :eventId
               and r.status in :statuses
               and r.testMode = false
            """)
    long sumLiveAmountByEventAndStatusIn(@Param("eventId") UUID eventId,
                                         @Param("statuses") Collection<PayoutRunStatus> statuses);

    /**
     * The event's most recent run — the one the attempt cap parks {@code BLOCKED}, carrying
     * the last failure reason. Highest {@code attempt} is the newest: attempts only ever
     * increase, and a RETRYING run reuses its own.
     */
    Optional<PayoutRun> findFirstByEventIdOrderByAttemptDesc(UUID eventId);

    /**
     * Has this event already been parked for exactly this reason? Guards the nightly sweep
     * against writing a second {@code BLOCKED} row — and sending a second email — for a block
     * the organizer has not cleared yet.
     */
    boolean existsByEventIdAndStatusAndFailureReason(UUID eventId, PayoutRunStatus status, String failureReason);

    /**
     * True when the event carries a {@code BLOCKED} run that needs a HUMAN — i.e. any blocked
     * run whose reason is not the self-healing {@code selfHealingReason}
     * ({@link PayoutBlockReason#NO_BANK_ACCOUNT}). Such an event must never re-candidate: the
     * attempt cap exists precisely so a doomed payout stops being retried nightly.
     *
     * <p>LIVE-mode runs only (V130). The cutover script parks every non-terminal test-era run
     * {@code blocked}, and an event that mixes test-era orders with live sales would otherwise
     * be excluded from live payouts forever by a run that never moved a real cent.
     */
    @Query("""
            select count(r) > 0 from PayoutRun r
             where r.eventId = :eventId
               and r.status = com.imin.iminapi.payout.PayoutRunStatus.BLOCKED
               and r.testMode = false
               and (r.failureReason is null or r.failureReason <> :selfHealingReason)
            """)
    boolean existsBlockedNeedingAHuman(@Param("eventId") UUID eventId,
                                       @Param("selfHealingReason") String selfHealingReason);
}
