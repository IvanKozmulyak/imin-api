package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.Membership;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
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
@RepositoryRestResource(exported = false)
public interface MembershipRepository extends Repository<Membership, UUID> {

    // ---- writes (used by ingestion + service layer) ----

    Membership save(Membership membership);

    @Modifying
    @Transactional
    @Query("delete from Membership m where m.membershipId = :id and m.orgId = :orgId")
    int deleteByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    // ---- tenant-scoped reads ----

    /**
     * Row-locks the membership for the caller's transaction; fan_features writers and requestErase take it first.
     * Native plain FOR UPDATE, kept rather than the dialect's PESSIMISTIC_WRITE, which renders FOR NO KEY UPDATE.
     */
    @Query(value = "SELECT * FROM memberships WHERE membership_id = :id AND org_id = :orgId FOR UPDATE",
            nativeQuery = true)
    Optional<Membership> lockByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.membershipId = :id and m.orgId = :orgId")
    Optional<Membership> findByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.consumerId = :consumerId")
    Optional<Membership> findByOrgIdAndConsumerId(@Param("orgId") UUID orgId,
                                                   @Param("consumerId") UUID consumerId);

    /**
     * Every membership held by these consumers, <b>across all organizers</b>.
     *
     * <p>Deliberately not tenant-scoped, and the only read here that is not.
     * The buyer preference centre (spec §4.4) is the buyer's own view of the
     * consent they hold, which is cross-organizer by definition — the same way
     * {@code GET /buyer/orders} crosses organizers. Every other query in this
     * interface derives its tenant from {@code AuthPrincipal.orgId}; this one is
     * scoped by the caller having proved they own the addresses behind those
     * consumer ids. Do not call it from an organizer-authenticated path.
     */
    @Query("select m from Membership m where m.consumerId in :consumerIds")
    List<Membership> findAllOrgsByConsumerIdIn(@Param("consumerIds") Collection<UUID> consumerIds);

    // A membership with an accepted Art.17 erasure request is not part of the audience for
    // the whole 30-day grace window: the listing, the export, every segment and the send
    // gate must already behave as if it were gone. Only the single-record reads
    // (findByIdAndOrgId, findErasureDue) still see it — DSAR itself has to keep working.
    // See ERASE_PENDING_EXCLUDED below and SendGateService.evaluate.

    // ---- CSV export: (created_at DESC, membership_id DESC); the paged list is MemberListQuery ----
    // search is split into its own methods: a nullable String fed into concat()/lower()
    // is bound by Hibernate as bytea when null, and Postgres rejects lower(bytea).
    // The no-search methods bind no :search param at all.

    @Query("""
            select m from Membership m
             where m.orgId = :orgId
               and m.status <> 'erase_pending'
               and (:lifecycle is null or m.lifecycle = :lifecycle)
             order by m.createdAt desc, m.membershipId desc
            """)
    List<Membership> listByOrg(@Param("orgId") UUID orgId,
                                @Param("lifecycle") String lifecycle,
                                Pageable pageable);

    @Query("""
            select m from Membership m
             where m.orgId = :orgId
               and m.status <> 'erase_pending'
               and (:lifecycle is null or m.lifecycle = :lifecycle)
               and (lower(m.displayName) like lower(concat('%', :search, '%'))
                    or lower(cast(m.membershipId as string)) like lower(concat('%', :search, '%'))
                    or m.consumerId in (select c.consumerId from Consumer c
                                         where c.normalizedEmail = :searchEmail))
             order by m.createdAt desc, m.membershipId desc
            """)
    List<Membership> searchByOrg(@Param("orgId") UUID orgId,
                                  @Param("lifecycle") String lifecycle,
                                  @Param("search") String search,
                                  @Param("searchEmail") String searchEmail,
                                  Pageable pageable);

    // ---- segment resolution ----

    @Query("select m from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.events >= 2")
    List<Membership> findRepeats(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.spendMinor >= 20000 and m.events >= 4")
    List<Membership> findVips(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.recencyDays >= 90 and m.consentStatus = 'subscribed'")
    List<Membership> findLapsed(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.events = 1")
    List<Membership> findFirstTimers(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.nps >= 9")
    List<Membership> findPromoters(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.noShow > 0")
    List<Membership> findBoughtNoShowed(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.recencyDays <= 30 and m.events <= 1")
    List<Membership> findNewest30d(@Param("orgId") UUID orgId);

    @Query("select m from Membership m where m.membershipId in :ids and m.orgId = :orgId")
    List<Membership> findByIdsAndOrgId(@Param("ids") Collection<UUID> ids,
                                        @Param("orgId") UUID orgId);

    // ---- segment live counts (the Audience tab asks for a number, not a page of people) ----

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.events >= 2")
    long countRepeats(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.spendMinor >= 20000 and m.events >= 4")
    long countVips(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.recencyDays >= 90 and m.consentStatus = 'subscribed'")
    long countLapsed(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.events = 1")
    long countFirstTimers(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.nps >= 9")
    long countPromoters(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.noShow > 0")
    long countBoughtNoShowed(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.status <> 'erase_pending' and m.recencyDays <= 30 and m.events <= 1")
    long countNewest30d(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.membershipId in :ids and m.orgId = :orgId and m.status <> 'erase_pending'")
    long countByIdsAndOrgId(@Param("ids") Collection<UUID> ids, @Param("orgId") UUID orgId);

    /**
     * Only the columns the segment rule engine reads. Custom (JSON-rule) segments have no
     * SQL predicate to count with, so this is the cheapest honest answer: one narrow row
     * per member instead of a fully hydrated entity graph per member per segment.
     */
    @Query("""
            select new com.imin.iminapi.audience.service.SegmentRuleRow(
                       m.membershipId, m.events, m.spendMinor, m.recencyDays, m.noShow,
                       m.nps, m.lifecycle, m.consentStatus, m.consentBasis)
              from Membership m
             where m.orgId = :orgId
               and m.status <> 'erase_pending'
            """)
    List<com.imin.iminapi.audience.service.SegmentRuleRow> findRuleRowsByOrgId(@Param("orgId") UUID orgId);

    /**
     * Rows of {@code [membershipId, eventId]}: the member holds a ticket that is not refunded or revoked,
     * on a non-test order of this org, for one of {@code eventIds}. Orders link to members by email.
     */
    @Query("""
            select distinct m.membershipId, o.eventId
              from Membership m, com.imin.iminapi.audience.model.Consumer c, com.imin.iminapi.model.Order o
             where c.consumerId = m.consumerId
               and o.orgId = m.orgId
               and o.emailNormalized = c.normalizedEmail
               and m.orgId = :orgId
               and o.eventId in :eventIds
               and o.testMode = false
               and exists (select 1 from com.imin.iminapi.model.Ticket t
                            where t.orderId = o.id and t.state not in ('refunded', 'revoked'))
            """)
    List<Object[]> findAttendedEventPairs(@Param("orgId") UUID orgId,
                                          @Param("eventIds") java.util.Collection<UUID> eventIds);

    // ---- metrics ----

    @Query("select count(m) from Membership m where m.orgId = :orgId")
    long countByOrgId(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.createdAt >= :since")
    long countCreatedSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.events > 0")
    long countBuyersByOrgId(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.events = 0")
    long countProspectsByOrgId(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.consentStatus = 'subscribed'")
    long countSubscribedByOrgId(@Param("orgId") UUID orgId);

    /**
     * Buyers who attended more than one event — the repeat-attendee numerator.
     * Org-scoped and unbounded in time on purpose: the denominator is every buyer,
     * so taking the numerator from the 56-day list-growth window (as the metrics
     * service used to) reported 0% for any org whose members joined earlier.
     */
    @Query("select count(m) from Membership m where m.orgId = :orgId and m.attended > 1")
    long countRepeatAttendeesByOrgId(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.consentBasis = 'explicit'")
    long countExplicitConsentByOrgId(@Param("orgId") UUID orgId);

    @Query("select count(m) from Membership m where m.orgId = :orgId and m.consentBasis = 'soft_opt_in'")
    long countSoftOptInByOrgId(@Param("orgId") UUID orgId);

    /**
     * SMS phones collected WITH consent: memberships that have opted in for SMS
     * (sms_consent_status = 'subscribed') and carry a phone number. Feeds the
     * marketing hub {@code smsPhones} tile.
     */
    @Query("""
            select count(m) from Membership m
             where m.orgId = :orgId
               and m.smsConsentStatus = 'subscribed'
               and m.phoneE164 is not null
            """)
    long countSmsSubscribedByOrgId(@Param("orgId") UUID orgId);

    /** All membership ids for an org — feeds the hub Send-Gate evaluation over ALL members. */
    @Query("select m.membershipId from Membership m where m.orgId = :orgId and m.status <> 'erase_pending'")
    List<UUID> findAllMembershipIdsByOrgId(@Param("orgId") UUID orgId);

    /** List-growth: count of new memberships per week over 8 weeks */
    @Query("""
            select m from Membership m
             where m.orgId = :orgId
               and m.createdAt >= :since
             order by m.createdAt asc
            """)
    List<Membership> findCreatedSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    // ---- DSAR erase ----

    /**
     * Every membership for a consumer, ACROSS ORGS — the fan-out a buyer-initiated
     * Art.17 erasure needs (§7.2 step 2).
     *
     * <p>Unscoped on purpose, and the second documented exception in this file
     * after {@link #findAllByPhoneE164}. The
     * justification is the same shape: the subject here is the <i>person</i>, not
     * one org's list. A buyer deleting their imin account is exercising Art.17
     * against every controller at once, so "which orgs hold a copy of this human"
     * is exactly the question being asked, and there is no orgId to scope it by
     * — the caller is discovering the org set, not filtering within one.
     *
     * <p>Only {@code BuyerAccountErasureService} may call this, and what it does
     * with each row is hand it straight back to the org-scoped
     * {@code DsarService.executeErase(orgId, membershipId, …)}.
     */
    @Query("select m from Membership m where m.consumerId = :consumerId")
    List<Membership> findAllByConsumerId(@Param("consumerId") UUID consumerId);

    // ---- SMS: phone-keyed lookup (platform-wide, M4 exception) ----

    /**
     * All memberships carrying this E.164 phone, ACROSS ORGS. Intentionally
     * unscoped: imin sends SMS from a single shared alphanumeric sender ID, so a
     * consumer's SMS consent/opt-out is a property of the PHONE, not of one org's
     * list. An inbound STOP must suppress every membership with that number, and
     * the marketing gate must treat any unsubscribe on that number as global.
     * Same M4 rationale as the shared Consumer / deliverability-suppression rows.
     * Exact-equality only (no lower/like) — safe from the PG null-String bytea trap.
     */
    @Query("select m from Membership m where m.phoneE164 = :phone")
    List<Membership> findAllByPhoneE164(@Param("phone") String phone);

    // ---- backfill ----

    /** All memberships for backfill/recompute — admin use, always scoped */
    @Query("select m from Membership m where m.orgId = :orgId and m.status <> 'erase_pending'")
    List<Membership> findAllByOrgId(@Param("orgId") UUID orgId);

    // ---- erasure job ----

    /** Memberships past their erasure grace period, ready for destructive cascade. */
    @Query("select m from Membership m where m.status = 'erase_pending' and m.eraseAt <= :now")
    List<Membership> findErasureDue(@Param("now") Instant now);
}
