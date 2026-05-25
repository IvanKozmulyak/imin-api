package com.imin.iminapi.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface RefundTicketRepository extends JpaRepository<RefundTicket, RefundTicketId> {

    @Query("select rt.ticketId from RefundTicket rt where rt.ticketId in :ticketIds")
    Set<UUID> findRefundedTicketIds(@Param("ticketIds") Collection<UUID> ticketIds);

    @Query("select rt.ticketId from RefundTicket rt where rt.refundId = :refundId")
    List<UUID> findTicketIdsByRefundId(@Param("refundId") UUID refundId);

    /**
     * Batch fetch of (refundId, ticketId) pairs for many refunds. Used by the
     * event-wide refunds endpoint to avoid N+1 ticket-id lookups when listing
     * an event's refunds.
     */
    @Query("select rt.refundId, rt.ticketId from RefundTicket rt where rt.refundId in :refundIds")
    List<Object[]> findRefundIdTicketIdPairs(@Param("refundIds") Collection<UUID> refundIds);
}
