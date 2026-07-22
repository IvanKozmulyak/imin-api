package com.imin.iminapi.repository;

import com.imin.iminapi.model.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * Org-wide order count (= completed payments) since a cutoff. Drives the
     * PAYMENTS_COMPLETED stage of the org-wide Meta signal-health funnel (spec §8).
     * Counts by {@code o.orgId} directly — the same org-wide aggregation convention
     * as {@link #sumRevenueAndCountByOrgInWindow} — so it does not join events.
     */
    @Query("select count(o) from Order o where o.orgId = :orgId and o.createdAt >= :since")
    long countByOrgIdSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

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
     * TRUE per-order last-touch revenue by campaign (V62): (utmCampaign, revenueMinor) for
     * the given campaign keys within an org. Replaces the visit-share approximation for
     * campaign revenue — each order's full total is counted once, against the campaign
     * whose link the buyer last arrived through.
     *
     * <p>The key is the campaign UUID as a string: the sender rewrites campaign links with
     * {@code utm_campaign=<campaign id>} ({@code UtmLinkRewriter} ← {@code EmailChannelSender}
     * passes {@code campaign.getId().toString()}).
     *
     * <p>Grouped + batched so the campaign list and the hub tiles cost ONE round-trip
     * rather than one per campaign. Campaigns with no attributed orders are simply absent
     * from the result — callers default them to 0. Untagged (organic) revenue matches no
     * key and is deliberately excluded rather than spread across campaigns.
     *
     * <p>Callers MUST skip this when {@code campaignKeys} is empty ({@code IN ()} is invalid SQL).
     */
    @Query("""
            select o.utmCampaign, coalesce(sum(o.totalMinor), 0) from Order o
             where o.orgId = :orgId
               and o.utmCampaign in :campaignKeys
             group by o.utmCampaign
            """)
    List<Object[]> sumRevenueByUtmCampaignIn(@Param("orgId") UUID orgId,
                                              @Param("campaignKeys") Collection<String> campaignKeys);

    /**
     * TRUE per-order last-touch revenue for ONE campaign (V62) — the {@code revMinor} on
     * campaign list/detail. Not windowed: a campaign's lifetime attributed revenue.
     * {@code utmCampaign} is the campaign UUID as a string (see
     * {@link #sumRevenueByUtmCampaignSince}). Org-scoped so a guessed campaign id from
     * another org can never sum a caller's revenue.
     */
    @Query("""
            select coalesce(sum(o.totalMinor), 0) from Order o
             where o.orgId = :orgId
               and o.utmCampaign = :campaign
            """)
    long sumTotalMinorByOrgIdAndUtmCampaign(@Param("orgId") UUID orgId,
                                             @Param("campaign") String campaign);

    /**
     * TRUE per-order last-touch revenue by channel (V62): (utmSource, revenueMinor) across
     * all of an org's tagged orders. Backs the channel-level attribution read-model, which
     * previously could only divide the org's whole revenue pool by tagged-visit SHARE.
     * Untagged orders are excluded — they belong to no channel.
     */
    @Query("""
            select o.utmSource, coalesce(sum(o.totalMinor), 0) from Order o
             where o.orgId = :orgId
               and o.utmSource is not null
             group by o.utmSource
            """)
    List<Object[]> sumRevenueByUtmSource(@Param("orgId") UUID orgId);

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
    // Every Order row represents issued tickets (orders are only created on successful
    // issuance), so no payment-intent filter: free (promo-zeroed / free-tier) orders
    // must reach the audience too. The old "stripePaymentIntentId is not null" filter
    // silently excluded them from the backfill.
    @Query("select distinct o.orgId, lower(o.email) from Order o where o.email is not null")
    List<Object[]> findDistinctOrgAndEmailPairs();
}
