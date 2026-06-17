package com.imin.iminapi.repository;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.stripe.StripeConnectState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    boolean existsBySlug(String slug);

    Optional<Organization> findBySlug(String slug);

    Optional<Organization> findByStripeAccountId(String stripeAccountId);

    /**
     * Orgs whose Connect mirror should be re-reconciled from Stripe: they have a connected
     * account, sit in a non-terminal {@code state}, and were last synced before {@code staleBefore}
     * (or never). Used by {@link com.imin.iminapi.stripe.StripeConnectStatusSweeper} as the
     * backstop for missed/failed v2 webhooks. Oldest-synced first so a backlog drains fairly.
     */
    @Query("""
            select o from Organization o
            where o.stripeAccountId is not null and o.stripeAccountId <> ''
              and o.stripeConnectState in :states
              and (o.stripeConnectStatusUpdatedAt is null or o.stripeConnectStatusUpdatedAt < :staleBefore)
            order by o.stripeConnectStatusUpdatedAt asc
            """)
    List<Organization> findConnectNeedingResync(@Param("states") Collection<StripeConnectState> states,
                                                 @Param("staleBefore") Instant staleBefore,
                                                 Pageable pageable);

    /**
     * Track B Phase 1 backfill candidates: orgs that have a connected account, are
     * payout-eligible ({@code stripePayoutsEnabled = true}), but have NOT yet had their
     * Stripe payout schedule flipped to manual ({@code stripePayoutScheduleManual = false}).
     * Driven by {@link com.imin.iminapi.payout.PayoutScheduleBackfillSweeper} — the only
     * backstop for a missed capability webhook on an already-ACTIVE account (which the
     * connect status sweeper excludes from re-sync).
     */
    @Query("""
            select o from Organization o
            where o.stripeAccountId is not null and o.stripeAccountId <> ''
              and o.stripePayoutsEnabled = true
              and o.stripePayoutScheduleManual = false
            """)
    List<Organization> findPayoutScheduleBackfillCandidates(Pageable pageable);

    /**
     * Track B Phase 1: write ONLY the {@code stripe_payout_schedule_manual} flag for one
     * org, in isolation. Used by {@link com.imin.iminapi.stripe.StripePayoutScheduleService#ensureManual}
     * from its own {@code REQUIRES_NEW} transaction so it never re-saves the OUTER
     * transaction's managed {@link Organization} instance — a full-entity save inside the
     * inner tx could strand the flag ahead of the mirror if the outer tx later rolls back.
     */
    @Modifying
    @Query("update Organization o set o.stripePayoutScheduleManual = true where o.id = :id")
    void markPayoutScheduleManual(@Param("id") UUID id);
}
