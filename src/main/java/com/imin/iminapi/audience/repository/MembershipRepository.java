package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.Membership;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link Membership} (M4).
 * Every read method takes orgId. No unscoped finders are exposed.
 * Does NOT extend JpaRepository/CrudRepository.
 */
public interface MembershipRepository extends Repository<Membership, UUID> {

    // ---- writes (used by ingestion + service layer) ----

    Membership save(Membership membership);

    @Modifying
    @Transactional
    @Query("delete from Membership m where m.membershipId = :id and m.orgId = :orgId")
    int deleteByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    // ---- webhook open/click projection (Phase 4 §2.5 step 5) ----
    // The recipient (and its membershipId) is resolved upstream by provider_message_id,
    // so these are id-keyed writes — no email-lookup query (Membership has no normalizedEmail).
    // @Modifying + @Transactional so the update runs in a writable tx even when the caller
    // reaches the repo without an outer transaction (repo extends the bare Repository<> marker).

    @Modifying
    @Transactional
    @Query("update Membership m set m.lastEmailOpen = :ts where m.membershipId = :id")
    void recordEmailOpen(@Param("id") UUID id, @Param("ts") Instant ts);

    @Modifying
    @Transactional
    @Query("update Membership m set m.lastEmailClick = :ts where m.membershipId = :id")
    void recordEmailClick(@Param("id") UUID id, @Param("ts") Instant ts);

    // ---- tenant-scoped reads ----

    @Query("select m from Membership m where m.membershipId = :id and m.orgId = :orgId")
    Optional<Membership> findByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.consumerId = :consumerId")
    Optional<Membership> findByOrgIdAndConsumerId(@Param("orgId") UUID orgId,
                                                   @Param("consumerId") UUID consumerId);

    // ---- keyset pagination (S2): sort by (created_at DESC, membership_id DESC) ----
    // search is split into its own methods: a nullable String fed into concat()/lower()
    // is bound by Hibernate as bytea when null, and Postgres rejects lower(bytea)
    // (H2 tolerates it). The no-search methods bind no :search param at all.

    @Query("""
            select m from Membership m
             where m.orgId = :orgId
               and (:lifecycle is null or m.lifecycle = :lifecycle)
             order by m.createdAt desc, m.membershipId desc
            """)
    List<Membership> listByOrg(@Param("orgId") UUID orgId,
                                @Param("lifecycle") String lifecycle,
                                Pageable pageable);

    @Query("""
            select m from Membership m
             where m.orgId = :orgId
               and (:lifecycle is null or m.lifecycle = :lifecycle)
               and (lower(m.displayName) like lower(concat('%', :search, '%'))
                    or lower(cast(m.membershipId as string)) like lower(concat('%', :search, '%')))
             order by m.createdAt desc, m.membershipId desc
            """)
    List<Membership> searchByOrg(@Param("orgId") UUID orgId,
                                  @Param("lifecycle") String lifecycle,
                                  @Param("search") String search,
                                  Pageable pageable);

    /**
     * Keyset page: rows with (createdAt, membershipId) strictly less than cursor values.
     */
    @Query("""
            select m from Membership m
             where m.orgId = :orgId
               and (:lifecycle is null or m.lifecycle = :lifecycle)
               and (m.createdAt < :cursorAt
                    or (m.createdAt = :cursorAt and m.membershipId < :cursorId))
             order by m.createdAt desc, m.membershipId desc
            """)
    List<Membership> listByOrgAfterCursor(@Param("orgId") UUID orgId,
                                           @Param("lifecycle") String lifecycle,
                                           @Param("cursorAt") Instant cursorAt,
                                           @Param("cursorId") UUID cursorId,
                                           Pageable pageable);

    @Query("""
            select m from Membership m
             where m.orgId = :orgId
               and (:lifecycle is null or m.lifecycle = :lifecycle)
               and (lower(m.displayName) like lower(concat('%', :search, '%'))
                    or lower(cast(m.membershipId as string)) like lower(concat('%', :search, '%')))
               and (m.createdAt < :cursorAt
                    or (m.createdAt = :cursorAt and m.membershipId < :cursorId))
             order by m.createdAt desc, m.membershipId desc
            """)
    List<Membership> searchByOrgAfterCursor(@Param("orgId") UUID orgId,
                                             @Param("lifecycle") String lifecycle,
                                             @Param("search") String search,
                                             @Param("cursorAt") Instant cursorAt,
                                             @Param("cursorId") UUID cursorId,
                                             Pageable pageable);

    // ---- segment resolution ----

    @Query("select m from Membership m where m.orgId = :orgId and m.events >= 2")
    List<Membership> findRepeats(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.spendMinor >= 20000 and m.events >= 4")
    List<Membership> findVips(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.recencyDays >= 90 and m.consentStatus = 'subscribed'")
    List<Membership> findLapsed(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.events = 1")
    List<Membership> findFirstTimers(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.nps >= 9")
    List<Membership> findPromoters(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.noShow > 0")
    List<Membership> findBoughtNoShowed(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.recencyDays <= 30 and m.events <= 1")
    List<Membership> findNewest30d(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.membershipId in :ids and m.orgId = :orgId")
    List<Membership> findByIdsAndOrgId(@Param("ids") Collection<UUID> ids,
                                        @Param("orgId") UUID orgId);

    // ---- metrics ----

    @Query("select count(m) from Membership m where m.orgId = :orgId")
    long countByOrgId(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.events > 0")
    long countBuyersByOrgId(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.events = 0")
    long countProspectsByOrgId(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.consentStatus = 'subscribed'")
    long countSubscribedByOrgId(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.consentBasis = 'explicit'")
    long countExplicitConsentByOrgId(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.consentBasis = 'soft_opt_in'")
    long countSoftOptInByOrgId(@Param("orgId") UUID orgId);

    /** List-growth: count of new memberships per week over 8 weeks */
    @Query("""
            select m from Membership m
             where m.orgId = :orgId
               and m.createdAt >= :since
             order by m.createdAt asc
            """)
    List<Membership> findCreatedSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    // ---- DSAR erase ----

    /** Count how many orgs reference this consumer — used to decide consumer row deletion */
    @Query("select count(m) from Membership m where m.consumerId = :consumerId")
    long countByConsumerId(@Param("consumerId") UUID consumerId);

    // ---- send gate (FR-SND-1) ----

    /**
     * Candidates for the send gate: subscribed + has lawful basis + not suppressed.
     * The gate service further filters against suppression_entries.
     */
    @Query("""
            select m from Membership m
             where m.orgId = :orgId
               and m.consentStatus = 'subscribed'
               and m.consentBasis is not null
               and m.membershipId in :membershipIds
            """)
    List<Membership> findSendCandidates(@Param("orgId") UUID orgId,
                                         @Param("membershipIds") Collection<UUID> membershipIds);

    // ---- backfill ----

    /** All memberships for backfill/recompute — admin use, always scoped */
    @Query("select m from Membership m where m.orgId = :orgId")
    List<Membership> findAllByOrgId(@Param("orgId") UUID orgId);

    // ---- erasure job ----

    /** Memberships past their erasure grace period, ready for destructive cascade. */
    @Query("select m from Membership m where m.status = 'erase_pending' and m.eraseAt <= :now")
    List<Membership> findErasureDue(@Param("now") Instant now);
}
