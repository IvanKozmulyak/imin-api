package com.imin.iminapi.payout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

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

    /**
     * Per-event existence guard for the candidate query: skip an event that already
     * has a {@code PLANNED}/{@code SUBMITTED}/{@code PAID} run. Bind
     * {@code [PLANNED, SUBMITTED, PAID]}.
     */
    boolean existsByEventIdAndStatusIn(UUID eventId, Collection<PayoutRunStatus> statuses);

    /** Runs for an event, e.g. to compute the next {@code attempt} after a FAILED run. */
    List<PayoutRun> findByEventId(UUID eventId);

    /**
     * Highest {@code attempt} recorded for an event, or {@code 0} when there are no runs.
     * Used to compute the next attempt (a fresh idempotency key after a FAILED run) without
     * loading every row.
     */
    @Query("select coalesce(max(r.attempt), 0) from PayoutRun r where r.eventId = :eventId")
    int maxAttemptByEventId(@Param("eventId") UUID eventId);

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
     */
    @Query("""
            select coalesce(sum(r.amountMinor), 0) from PayoutRun r
             where r.eventId = :eventId
               and r.status in :statuses
            """)
    long sumAmountByEventAndStatusIn(@Param("eventId") UUID eventId,
                                     @Param("statuses") Collection<PayoutRunStatus> statuses);
}
