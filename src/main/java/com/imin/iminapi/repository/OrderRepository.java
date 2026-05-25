package com.imin.iminapi.repository;

import com.imin.iminapi.model.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByToken(String token);
    Optional<Order> findByStripePaymentIntentId(String paymentIntentId);
    Optional<Order> findByStripeSessionId(String sessionId);

    List<Order> findByEventIdOrderByCreatedAtDesc(UUID eventId);

    List<Order> findByEventIdOrderByCreatedAtDesc(UUID eventId, Pageable pageable);

    /** Gross revenue (sum of order totals) for an event, in minor units. Includes refunded amounts. */
    @Query("select coalesce(sum(o.totalMinor), 0) from Order o where o.eventId = :eventId")
    long sumTotalMinorByEventId(@Param("eventId") UUID eventId);

    /**
     * Sum of platform application fees ({@code application_fee_minor}) Stripe
     * deducted across all orders for an event. Snapshot — does not account for
     * refunded fee portions; net those out via
     * {@code RefundRepository.sumSucceededRefundApplicationFeeMinorByEventId}.
     */
    @Query("select coalesce(sum(o.applicationFeeMinor), 0) from Order o where o.eventId = :eventId")
    long sumApplicationFeeMinorByEventId(@Param("eventId") UUID eventId);

    /**
     * Created-at + total-minor pairs for orders since {@code since}. Used by the
     * sales-velocity service to bucket by day. Returned as {@code Object[]} to
     * avoid a per-row entity hydration cost — the only columns the caller needs
     * are the timestamp and the amount.
     */
    @Query("""
            select o.createdAt, o.totalMinor from Order o
             where o.eventId = :eventId
               and o.createdAt >= :since
             order by o.createdAt asc
            """)
    List<Object[]> findCreatedAtAndTotalSince(@Param("eventId") UUID eventId,
                                              @Param("since") Instant since);

    @Query("""
            select o from Order o
             where lower(o.email) = lower(:email)
               and (:eventId is null or o.eventId = :eventId)
               and o.createdAt > :cutoff
             order by o.createdAt desc
            """)
    List<Order> findRecentForRecovery(@Param("email") String email,
                                       @Param("eventId") UUID eventId,
                                       @Param("cutoff") Instant cutoff);
}
