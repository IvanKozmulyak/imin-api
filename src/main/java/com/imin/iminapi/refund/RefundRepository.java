package com.imin.iminapi.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);

    Optional<Refund> findByStripeRefundId(String stripeRefundId);

    List<Refund> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    /** SUCCEEDED refund amount and booking-fee part per order, one row per order that has any. */
    @Query("""
            select new com.imin.iminapi.refund.RefundOrderSums(
                       r.orderId, sum(r.amountMinor), sum(r.applicationFeeRefundMinor))
              from Refund r
             where r.orderId in :orderIds
               and r.status = com.imin.iminapi.refund.RefundStatus.SUCCEEDED
             group by r.orderId
            """)
    List<RefundOrderSums> sumSucceededAmountAndFeeByOrderIds(@Param("orderIds") Collection<UUID> orderIds);

    /**
     * All refunds for any order in the given event, newest first. Includes the
     * order's email and short identifier so callers can render a per-event
     * refund history without an N+1 lookup against orders. Tuple shape:
     * {@code [Refund refund, String email, UUID orderId]}.
     */
    @Query("""
            select r, o.email, o.id from Refund r
              join com.imin.iminapi.model.Order o on o.id = r.orderId
             where o.eventId = :eventId
             order by r.createdAt desc
            """)
    List<Object[]> findByEventIdWithOrder(@Param("eventId") UUID eventId);

    /**
     * Sum of SUCCEEDED refund amounts (minor units) for all orders of an event.
     * Used by the event-overview Revenue card to net out refunds from gross.
     * REQUESTED / PENDING / FAILED / CANCELED rows are excluded.
     */
    @Query("""
            select coalesce(sum(r.amountMinor), 0) from Refund r
             where r.orderId in (select o.id from com.imin.iminapi.model.Order o where o.eventId = :eventId)
               and r.status = com.imin.iminapi.refund.RefundStatus.SUCCEEDED
            """)
    long sumSucceededRefundMinorByEventId(@Param("eventId") UUID eventId);

    /** {@link #sumSucceededRefundMinorByEventId} for a page of events: [eventId, sum]. */
    @Query("""
            select o.eventId, coalesce(sum(r.amountMinor), 0) from Refund r
              join com.imin.iminapi.model.Order o on o.id = r.orderId
             where o.eventId in :eventIds
               and r.status = com.imin.iminapi.refund.RefundStatus.SUCCEEDED
             group by o.eventId
            """)
    List<Object[]> sumSucceededRefundMinorByEventIds(@Param("eventIds") Collection<UUID> eventIds);

    /**
     * Sum of platform application-fee refunds (the platform-cut portion that
     * went back to the buyer) across SUCCEEDED refunds for an event. Used to
     * net the after-fees revenue when an order is partially or fully refunded.
     */
    @Query("""
            select coalesce(sum(r.applicationFeeRefundMinor), 0) from Refund r
             where r.orderId in (select o.id from com.imin.iminapi.model.Order o where o.eventId = :eventId)
               and r.status = com.imin.iminapi.refund.RefundStatus.SUCCEEDED
            """)
    long sumSucceededRefundApplicationFeeMinorByEventId(@Param("eventId") UUID eventId);

    /**
     * One row {@code [amountMinor, applicationFeeRefundMinor]} over the SUCCEEDED refunds of
     * the org's orders created in {@code [since, until)}, all modes: refunds follow their
     * order's window, not the refund date.
     */
    @Query("""
            select coalesce(sum(r.amountMinor), 0), coalesce(sum(r.applicationFeeRefundMinor), 0) from Refund r
             where r.orderId in (select o.id from com.imin.iminapi.model.Order o
                                  where o.orgId = :orgId
                                    and o.createdAt >= :since
                                    and o.createdAt < :until)
               and r.status = com.imin.iminapi.refund.RefundStatus.SUCCEEDED
            """)
    List<Object[]> sumSucceededRefundAndFeeByOrgInWindow(@Param("orgId") UUID orgId,
                                                         @Param("since") java.time.Instant since,
                                                         @Param("until") java.time.Instant until);

    /**
     * Per-refund (updatedAt, amountMinor) pairs for SUCCEEDED refunds of an event,
     * since {@code since}. Used by the velocity service to bucket refunds by day
     * (subtracted from the gross revenue bar for the same day). {@code updatedAt}
     * is the timestamp the refund flipped to SUCCEEDED.
     */
    @Query("""
            select r.updatedAt, r.amountMinor from Refund r
             where r.orderId in (select o.id from com.imin.iminapi.model.Order o where o.eventId = :eventId)
               and r.status = com.imin.iminapi.refund.RefundStatus.SUCCEEDED
               and r.updatedAt >= :since
            """)
    List<Object[]> findSucceededRefundUpdatedAtAndAmountSince(@Param("eventId") UUID eventId,
                                                              @Param("since") java.time.Instant since);

    /**
     * Race-safe status transition. Returns 1 if this caller won the transition,
     * 0 if another transaction already advanced the row. Used by the webhook
     * handler to ensure inventory release runs exactly once per refund.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update Refund r
               set r.status = :next
             where r.id = :id
               and r.status = :expected
            """)
    int updateStatusIfCurrent(@Param("id") UUID id,
                              @Param("expected") RefundStatus expected,
                              @Param("next") RefundStatus next);

    @Query("""
        select coalesce(sum(r.amountMinor), 0) from Refund r
        where r.orderId = :orderId
          and r.status in (com.imin.iminapi.refund.RefundStatus.REQUESTED,
                           com.imin.iminapi.refund.RefundStatus.PENDING,
                           com.imin.iminapi.refund.RefundStatus.SUCCEEDED)
    """)
    long sumActiveAmountByOrderId(java.util.UUID orderId);

    /**
     * Application-fee refunds already committed for an order (REQUESTED/PENDING/SUCCEEDED),
     * the fee-side mirror of {@link #sumActiveAmountByOrderId}. The per-refund fee share is a
     * rounded proportion, so N refunds of one order can sum to MORE than the original fee
     * (three thirds of 149 round to 50+50+50 = 150); Stripe then rejects the last
     * applicationFees().refunds().create with an invalid_request_error. Subtracting this from
     * the order's fee gives the remaining unrefunded fee to clamp against.
     */
    @Query("""
        select coalesce(sum(r.applicationFeeRefundMinor), 0) from Refund r
        where r.orderId = :orderId
          and r.status in (com.imin.iminapi.refund.RefundStatus.REQUESTED,
                           com.imin.iminapi.refund.RefundStatus.PENDING,
                           com.imin.iminapi.refund.RefundStatus.SUCCEEDED)
    """)
    long sumActiveApplicationFeeRefundMinorByOrderId(java.util.UUID orderId);

    /**
     * The org's outstanding platform-funded refunds: SUCCEEDED refunds imin paid out of its own
     * balance and has not yet pulled back off the connected account. Org-level, not event-level —
     * the refund may belong to an event that has already paid out. Returned as rows, not a sum,
     * because recovery reverses the destination transfer of each refund's own charge.
     */
    @Query("""
        select r from Refund r
        where r.platformFunded = true
          and r.status = com.imin.iminapi.refund.RefundStatus.SUCCEEDED
          and r.recoveredAt is null
          and r.orderId in (select o.id from com.imin.iminapi.model.Order o where o.orgId = :orgId)
        order by r.createdAt
    """)
    List<Refund> findUnrecoveredPlatformFundedByOrgId(@Param("orgId") UUID orgId);

    /**
     * Every org that still owes imin an unrecovered platform-funded refund. The payout sweep
     * runs recovery for these BEFORE the candidate loop: an org whose events have all paid out
     * has no payout candidate, so recovery driven from the per-event path alone would never
     * reach it and the debt would sit forever.
     */
    @Query("""
        select distinct o.orgId from Refund r
          join com.imin.iminapi.model.Order o on o.id = r.orderId
        where r.platformFunded = true
          and r.status = com.imin.iminapi.refund.RefundStatus.SUCCEEDED
          and r.recoveredAt is null
    """)
    List<UUID> findOrgIdsWithUnrecoveredPlatformFunded();

    // ── Refund attempts: every write below is conditional on the row still being unresolved ──

    /**
     * Records what Stripe answered for an attempt. Lands only while the row is REQUESTED and carries
     * no other Stripe id, so a webhook that already moved the row wins and this returns 0.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Refund r
               set r.stripeRefundId = :sid,
                   r.stripeChargeId = :chargeId,
                   r.status = :status,
                   r.failureCode = :failureCode,
                   r.failureMessage = :failureMessage
             where r.id = :id
               and r.status = com.imin.iminapi.refund.RefundStatus.REQUESTED
               and (r.stripeRefundId is null or r.stripeRefundId = :sid)
            """)
    int recordOutcome(@Param("id") UUID id,
                      @Param("sid") String stripeRefundId,
                      @Param("chargeId") String stripeChargeId,
                      @Param("status") RefundStatus status,
                      @Param("failureCode") String failureCode,
                      @Param("failureMessage") String failureMessage);

    /**
     * Stripe refused the attempt, so no refund exists: FAILED, and the client key is renamed to
     * {@code failedKey} so a retry with the same key opens a fresh attempt.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Refund r
               set r.status = com.imin.iminapi.refund.RefundStatus.FAILED,
                   r.failureCode = :failureCode,
                   r.failureMessage = :failureMessage,
                   r.idempotencyKey = :failedKey
             where r.id = :id
               and r.status = com.imin.iminapi.refund.RefundStatus.REQUESTED
               and r.stripeRefundId is null
            """)
    int recordRefusal(@Param("id") UUID id,
                      @Param("failureCode") String failureCode,
                      @Param("failureMessage") String failureMessage,
                      @Param("failedKey") String failedKey);

    /** The connected balance was short: the next create is the platform-funded variant. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Refund r
               set r.platformFunded = true,
                   r.stripeAttemptAt = :now,
                   r.stripeAttempts = r.stripeAttempts + 1
             where r.id = :id
               and r.status = com.imin.iminapi.refund.RefundStatus.REQUESTED
               and r.stripeRefundId is null
               and r.platformFunded = false
            """)
    int switchToPlatform(@Param("id") UUID id, @Param("now") java.time.Instant now);

    /**
     * Claim of an unresolved attempt for one reconciler pass: lands only while the row still holds the
     * attempt time {@code seenAttemptAt} and that time is older than {@code cutoff}, so a row another
     * claimer (or the live call) has just bumped is never claimed again until it ages past the cutoff.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Refund r
               set r.stripeAttemptAt = :now,
                   r.stripeAttempts = r.stripeAttempts + 1
             where r.id = :id
               and r.status = com.imin.iminapi.refund.RefundStatus.REQUESTED
               and r.stripeRefundId is null
               and r.stripeAttemptAt = :seen
               and r.stripeAttemptAt < :cutoff
            """)
    int claimForReconcile(@Param("id") UUID id,
                          @Param("seen") java.time.Instant seenAttemptAt,
                          @Param("cutoff") java.time.Instant cutoff,
                          @Param("now") java.time.Instant now);

    /** A refund object Stripe answered failed/canceled frees its client key, as a refusal does. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Refund r set r.idempotencyKey = :failedKey where r.id = :id")
    int freeClientKey(@Param("id") UUID id, @Param("failedKey") String failedKey);

    /** Links a Stripe refund found by its {@code imin_refund_id} metadata to its still-unresolved row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Refund r
               set r.stripeRefundId = :sid,
                   r.stripeChargeId = :chargeId
             where r.id = :id
               and r.status = com.imin.iminapi.refund.RefundStatus.REQUESTED
               and r.stripeRefundId is null
            """)
    int adoptStripeRefund(@Param("id") UUID id,
                          @Param("sid") String stripeRefundId,
                          @Param("chargeId") String stripeChargeId);

    /**
     * Attempts with no known Stripe outcome, last touched before {@code cutoff}, on orders paid in
     * the running key's mode; oldest first. Rows written before V179 have no attempt time and never match.
     */
    @Query("""
            select r from Refund r
              join com.imin.iminapi.model.Order o on o.id = r.orderId
             where r.status = com.imin.iminapi.refund.RefundStatus.REQUESTED
               and r.stripeRefundId is null
               and r.stripeAttemptAt is not null
               and r.stripeAttemptAt < :cutoff
               and o.testMode = :testMode
             order by r.stripeAttemptAt
            """)
    List<Refund> findUnresolvedAttempts(@Param("cutoff") java.time.Instant cutoff,
                                        @Param("testMode") boolean testMode,
                                        org.springframework.data.domain.Pageable page);
}
