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

    boolean existsByOrderIdAndStatus(UUID orderId, RefundRequestStatus status);

    Optional<RefundRequest> findFirstByOrderIdAndStatus(UUID orderId, RefundRequestStatus status);
}
