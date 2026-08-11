package com.imin.iminapi.refund;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    Optional<RefundRequest> findByIdAndOrgId(UUID id, UUID orgId);

    @Query("""
        select rr from RefundRequest rr
        where rr.orgId = :orgId
          and (:eventId is null or rr.eventId = :eventId)
          and rr.status in :statuses
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
     * 500s with {@code function lower(bytea) does not exist}, while passing on H2. Here
     * {@code :search} is guaranteed non-null by the caller, so the trap can't fire.
     *
     * <p>Matches an exact (already normalised, uppercase) reference OR a buyer-email
     * substring — the two things an operator has when a customer gets in touch.
     */
    @Query("""
        select rr from RefundRequest rr
        where rr.orgId = :orgId
          and (:eventId is null or rr.eventId = :eventId)
          and rr.status in :statuses
          and (rr.reference = :search
               or lower(rr.buyerEmail) like lower(concat('%', :search, '%')))
        order by rr.createdAt desc, rr.id desc
    """)
    List<RefundRequest> pageSearch(@Param("orgId") UUID orgId,
                                   @Param("eventId") UUID eventId,
                                   @Param("statuses") List<RefundRequestStatus> statuses,
                                   @Param("search") String search,
                                   Pageable pageable);

    boolean existsByOrderIdAndStatus(UUID orderId, RefundRequestStatus status);

    Optional<RefundRequest> findFirstByOrderIdAndStatus(UUID orderId, RefundRequestStatus status);

    boolean existsByReference(String reference);
}
