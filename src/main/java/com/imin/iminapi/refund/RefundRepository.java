package com.imin.iminapi.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);

    Optional<Refund> findByStripeRefundId(String stripeRefundId);

    List<Refund> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

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
}
