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

    /** Number of orders (= completed payments) for an event. Drives the funnel's PAYMENTS_COMPLETED stage. */
    long countByEventId(UUID eventId);

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

    /**
     * (revenueMinor, orderCount) for an org over a half-open time window.
     * Used by the dashboard "This cycle" and "Business" cards.
     */
    @Query("""
            select coalesce(sum(o.totalMinor), 0), count(o) from Order o
             where o.orgId = :orgId
               and o.createdAt >= :since
               and o.createdAt < :until
            """)
    List<Object[]> sumRevenueAndCountByOrgInWindow(@Param("orgId") UUID orgId,
                                                   @Param("since") Instant since,
                                                   @Param("until") Instant until);

    /**
     * Total gross revenue (sum of order totals, minor units) across all of an
     * org's orders. Used as the attribution pool that UTM channels divide up.
     */
    @Query("select coalesce(sum(o.totalMinor), 0) from Order o where o.orgId = :orgId")
    long sumTotalMinorByOrgId(@Param("orgId") UUID orgId);

    /**
     * All orders for an org scoped to a buyer's normalized email.
     * Used by the audience membership projector (S1 derive-from-source).
     */
    @Query("select o from Order o where o.orgId = :orgId and lower(o.email) = :normalizedEmail order by o.createdAt asc")
    List<com.imin.iminapi.model.Order> findByOrgIdAndNormalizedEmail(@Param("orgId") UUID orgId,
                                                                      @Param("normalizedEmail") String normalizedEmail);

    /**
     * Distinct (lowercased) buyer emails with their order-count for an org since
     * a cutoff. Lets us compute repeat-rate in Java without a window function.
     */
    @Query("""
            select lower(o.email), count(o) from Order o
             where o.orgId = :orgId
               and o.createdAt >= :since
             group by lower(o.email)
            """)
    List<Object[]> orderCountsByEmailSince(@Param("orgId") UUID orgId,
                                            @Param("since") Instant since);

    /**
     * Distinct (orgId, lower(email)) pairs for all paid orders.
     * Used by {@link com.imin.iminapi.audience.service.AudienceBackfillJob}.
     */
    @Query("select distinct o.orgId, lower(o.email) from Order o where o.stripePaymentIntentId is not null")
    List<Object[]> findDistinctOrgAndEmailPairs();
}
