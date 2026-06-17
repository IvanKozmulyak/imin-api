package com.imin.iminapi.settlement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/write access to the settlements read-model. {@code exported = false}
 * keeps Spring Data REST from auto-exposing a public CRUD endpoint for the
 * table — every repo in this codebase sets this.
 *
 * <p>The webhook handler upserts by {@link #findByStripeObjectId(String)}; the
 * payouts endpoints read org-scoped history and the summary aggregates.
 *
 * <p>NOTE: {@code status} / {@code objectType} are persisted via an
 * {@code AttributeConverter}, so JPQL filters bind the enum as a parameter
 * ({@code = :status}) rather than a path literal — Spring Data routes the bound
 * value through the converter to the lowercase column form.
 */
@RepositoryRestResource(exported = false)
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    /** Upsert key: the one row mirroring a given Stripe tr_/po_ object. */
    Optional<Settlement> findByStripeObjectId(String stripeObjectId);

    /** Org-scoped payout history, newest first, paginated. */
    Page<Settlement> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);

    /** Org-scoped payout history, newest first (unpaged convenience). */
    List<Settlement> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    /**
     * Org-scoped payout history of a single object type, newest first, paginated.
     * The {@code /payouts} history list binds {@code PAYOUT} here so only genuine
     * payout rows surface — transfer rows feed the summary "in transit" figure
     * and dispute/refund annotations never appear as history rows.
     */
    Page<Settlement> findByOrgIdAndObjectTypeOrderByCreatedAtDesc(
            UUID orgId, SettlementObjectType objectType, Pageable pageable);

    /** Rows of a given status for an org (e.g. all IN_TRANSIT), newest first. */
    List<Settlement> findByOrgIdAndStatusOrderByCreatedAtDesc(UUID orgId, SettlementStatus status);

    /**
     * Sum of settlement amounts (minor units) for an org in a single status.
     * Drives summary tiles like inTransit / pending. Bind {@code status} as a
     * parameter so the converter maps it to the lowercase column value.
     */
    @Query("""
            select coalesce(sum(s.amountMinor), 0) from Settlement s
             where s.orgId = :orgId
               and s.status = :status
            """)
    long sumAmountByOrgAndStatus(@Param("orgId") UUID orgId,
                                 @Param("status") SettlementStatus status);

    /**
     * (count, summed amount minor) of an org's PAID payouts whose {@code paidAt}
     * falls in the half-open window {@code [since, until)}. Drives the summary
     * "this month" tile. Filtering on PAID status + the PAYOUT object type means
     * only money actually banked this month counts — REVERSED/FAILED rows and
     * transfer/dispute/refund annotations are excluded, and a month-boundary
     * payout is bucketed by its arrival (paid_at), not its created_at.
     * Tuple shape: {@code [long count, long amountMinor]}.
     */
    @Query("""
            select count(s), coalesce(sum(s.amountMinor), 0) from Settlement s
             where s.orgId = :orgId
               and s.objectType = :objectType
               and s.status = :status
               and s.paidAt >= :since
               and s.paidAt < :until
            """)
    List<Object[]> countAndSumPaidByOrgAndTypeInWindow(@Param("orgId") UUID orgId,
                                                        @Param("objectType") SettlementObjectType objectType,
                                                        @Param("status") SettlementStatus status,
                                                        @Param("since") Instant since,
                                                        @Param("until") Instant until);

    /**
     * Track B Phase 2 dispute/hold guard (plan §4.2.3.1): does the org have any
     * open dispute on a backing destination-charge TRANSFER row? Dispute
     * annotations from {@code ingestDispute} land on the {@code transfer} row
     * (flipped to {@code failed} while funds are at risk), so a non-zero count of
     * {@code object_type='transfer' AND status='failed'} for the org means a payout
     * must be skipped this tick.
     *
     * <p>Deliberately scoped to {@code TRANSFER}: a {@code FAILED} <i>payout</i> row
     * is a bank-routing failure (closed external account), NOT a dispute, and must
     * NOT block future payouts. Bind {@code FAILED} as a parameter so the converter
     * maps it to the lowercase column value.
     */
    @Query("""
            select count(s) from Settlement s
             where s.orgId = :orgId
               and s.objectType = com.imin.iminapi.settlement.SettlementObjectType.TRANSFER
               and s.status = :failed
            """)
    long countOpenTransferDisputes(@Param("orgId") UUID orgId,
                                   @Param("failed") SettlementStatus failed);
}
