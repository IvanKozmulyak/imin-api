package com.imin.iminapi.refund;

import com.imin.iminapi.refund.dto.RefundRequestPendingCountResponse;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    /** The org / event / status filter every operator list and count shares, so "pending" means one thing. */
    String ORG_EVENT_STATUS = """
        rr.orgId = :orgId
          and (:eventId is null or rr.eventId = :eventId)
          and rr.status in :statuses
    """;

    Optional<RefundRequest> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Row-locks the request until commit, before it is loaded (a read, so the entity is fresh), so an approve
     * or reject and the cancellation's {@link #closePendingForCancellation} decide one request in turn.
     */
    @Query(value = "select id from refund_requests where id = :id for update", nativeQuery = true)
    Optional<UUID> lockForDecision(@Param("id") UUID id);

    /** True when imin cancelled the event (it has an {@code event_cancellations} row); a hand-set CANCELLED is not. */
    @Query(value = "select exists (select 1 from event_cancellations where event_id = :eventId)", nativeQuery = true)
    boolean isEventCancelledThroughImin(@Param("eventId") UUID eventId);

    /**
     * Decides the order's PENDING request as approved by imin once its cancellation item settles: {@code refundId}
     * is the refund that settles it, null when the bank or imin's team does ({@code note} names the item state).
     * Conditional on PENDING, so a request an organizer decided first keeps that decision.
     */
    @Modifying
    @Transactional
    @Query(value = """
        update refund_requests
           set status = 'APPROVED', decided_at = :now, decided_by_user_id = null,
               decision_note = :note, refund_id = :refundId, pending_marker = null, updated_at = :now
         where order_id = :orderId and status = 'PENDING'
    """, nativeQuery = true)
    int closePendingForCancellation(@Param("orderId") UUID orderId, @Param("refundId") UUID refundId,
                                    @Param("note") String note, @Param("now") Instant now);

    /**
     * Refund requests on these orders, oldest first, <b>across organizers</b>.
     *
     * <p>For the buyer's own order list (spec §4.6), which crosses organizers by
     * design the way {@code GET /buyer/orders} does. Ascending so a caller
     * folding into a map keyed by order id ends up holding the latest. Callers
     * must have already proved the orders belong to the buyer.
     */
    List<RefundRequest> findByOrderIdInOrderByCreatedAtAsc(Collection<UUID> orderIds);

    @Query("""
        select rr from RefundRequest rr
        where """ + ORG_EVENT_STATUS + """
        order by rr.createdAt desc, rr.id desc
    """)
    List<RefundRequest> page(@Param("orgId") UUID orgId,
                             @Param("eventId") UUID eventId,
                             @Param("statuses") List<RefundRequestStatus> statuses,
                             Pageable pageable);

    /**
     * Operator search variant. DELIBERATELY a separate query from {@link #page} rather
     * than a {@code (:search is null or ...)} branch: a NULLABLE String parameter reaching
     * {@code lower}/{@code concat}/{@code like} type-infers to bytea on real Postgres and
     * 500s with {@code function lower(bytea) does not exist}. Here
     * {@code :search} is guaranteed non-null by the caller, so the trap can't fire.
     *
     * <p>Matches an exact reference OR a buyer-email substring — the two things an operator has
     * when a customer gets in touch. The two are SEPARATE parameters on purpose:
     * {@code reference} is the operator's term run through
     * {@code RefundReferenceGenerator.normalize} (uppercased, {@code REQ-} restored), while
     * {@code term} is what they actually typed. Folding them into one parameter meant an email
     * fragment that happens to look like a reference body — {@code mark-22} normalises to
     * {@code REQ-MARK-22} — replaced the search term outright, so the email match ran against a
     * string the operator never typed and returned nothing. Both callers pass both; when the
     * term is not reference-shaped, {@code reference} is simply the raw term and matches no row.
     */
    @Query("""
        select rr from RefundRequest rr
        where """ + ORG_EVENT_STATUS + """
          and (rr.reference = :reference
               or lower(rr.buyerEmail) like lower(concat('%', :term, '%')))
        order by rr.createdAt desc, rr.id desc
    """)
    List<RefundRequest> pageSearch(@Param("orgId") UUID orgId,
                                   @Param("eventId") UUID eventId,
                                   @Param("statuses") List<RefundRequestStatus> statuses,
                                   @Param("reference") String reference,
                                   @Param("term") String term,
                                   Pageable pageable);

    /** Counts per event over the same filter as {@link #page}; one aggregate, newest request first. */
    @Query("""
        select new com.imin.iminapi.refund.dto.RefundRequestPendingCountResponse$EventCount(
                   rr.eventId, e.name, count(rr))
        from RefundRequest rr
        left join Event e on e.id = rr.eventId
        where """ + ORG_EVENT_STATUS + """
        group by rr.eventId, e.name
        order by max(rr.createdAt) desc, rr.eventId
    """)
    List<RefundRequestPendingCountResponse.EventCount> countByEvent(
            @Param("orgId") UUID orgId,
            @Param("eventId") UUID eventId,
            @Param("statuses") List<RefundRequestStatus> statuses);

    boolean existsByOrderIdAndStatus(UUID orderId, RefundRequestStatus status);

    Optional<RefundRequest> findFirstByOrderIdAndStatus(UUID orderId, RefundRequestStatus status);
}
