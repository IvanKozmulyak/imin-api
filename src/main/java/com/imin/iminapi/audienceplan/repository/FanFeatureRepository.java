package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.FanFeature;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface FanFeatureRepository extends Repository<FanFeature, UUID> {

    FanFeature save(FanFeature feature);

    Optional<FanFeature> findById(UUID membershipId);

    @Modifying
    @Transactional
    @Query("delete from FanFeature f where f.membershipId = :membershipId")
    int deleteByMembershipId(@Param("membershipId") UUID membershipId);

    /** Empties the profiling columns to the calculator's objected output; the caller holds the membership lock. */
    @Modifying
    @Transactional
    @Query("update FanFeature f set f.taste = '{}', f.cities = '[]', f.formats = '[]' where f.membershipId = :membershipId")
    int clearProfiling(@Param("membershipId") UUID membershipId);

    // ---- recompute targets: every live membership, keyset-paged by id ----
    // First page and later pages are separate queries so no nullable cursor is ever bound.

    @Query("""
            select new com.imin.iminapi.audienceplan.repository.FanFeatureTarget(
                       m.membershipId, m.orgId, c.normalizedEmail, m.objectedProfiling)
              from Membership m, Consumer c
             where c.consumerId = m.consumerId
               and m.status <> 'erase_pending'
             order by m.membershipId
            """)
    List<FanFeatureTarget> findTargetsFirstPage(Pageable page);

    @Query("""
            select new com.imin.iminapi.audienceplan.repository.FanFeatureTarget(
                       m.membershipId, m.orgId, c.normalizedEmail, m.objectedProfiling)
              from Membership m, Consumer c
             where c.consumerId = m.consumerId
               and m.status <> 'erase_pending'
               and m.membershipId > :after
             order by m.membershipId
            """)
    List<FanFeatureTarget> findTargetsAfter(@Param("after") UUID after, Pageable page);

    @Query("""
            select new com.imin.iminapi.audienceplan.repository.FanFeatureTarget(
                       m.membershipId, m.orgId, c.normalizedEmail, m.objectedProfiling)
              from Membership m, Consumer c
             where c.consumerId = m.consumerId
               and m.status <> 'erase_pending'
               and m.membershipId = :membershipId
               and m.orgId = :orgId
            """)
    Optional<FanFeatureTarget> findTarget(@Param("orgId") UUID orgId, @Param("membershipId") UUID membershipId);

    /** Live memberships with no feature row written since {@code cutoff}. */
    @Query("""
            select count(m) from Membership m
             where m.status <> 'erase_pending'
               and not exists (select 1 from FanFeature f
                                where f.membershipId = m.membershipId
                                  and f.updatedAt >= :cutoff)
            """)
    long countStale(@Param("cutoff") Instant cutoff);
    // ConsentGate: one shared SQL filter; see ConsentGateSql for the parameters.

    @Query(value = ConsentGateSql.MAILABLE_IDS, nativeQuery = true)
    List<Object> findMailableMembershipIds(@Param("orgId") UUID orgId,
            @Param("namedSources") Collection<String> namedSources,
            @Param("namedVersions") Collection<String> namedVersions,
            @Param("provenanceSources") Collection<String> provenanceSources,
            @Param("textVersionSources") Collection<String> textVersionSources,
            @Param("personSources") Collection<String> personSources,
            @Param("softOptInBases") Collection<String> softOptInBases,
            @Param("cutoffAt") Instant cutoffAt,
            @Param("cutoffDate") LocalDate cutoffDate);

    /** Rows of {@code [reason, basis, count]}; a null reason is mailable. */
    @Query(value = ConsentGateSql.REASON_BASIS_COUNTS, nativeQuery = true)
    List<Object[]> countVerdictsByReasonAndBasis(@Param("orgId") UUID orgId,
            @Param("namedSources") Collection<String> namedSources,
            @Param("namedVersions") Collection<String> namedVersions,
            @Param("provenanceSources") Collection<String> provenanceSources,
            @Param("textVersionSources") Collection<String> textVersionSources,
            @Param("personSources") Collection<String> personSources,
            @Param("softOptInBases") Collection<String> softOptInBases,
            @Param("cutoffAt") Instant cutoffAt,
            @Param("cutoffDate") LocalDate cutoffDate);

    /** Rows of {@code [membership_id, reason]} for those ids that belong to the org. */
    @Query(value = ConsentGateSql.REASONS_FOR_IDS, nativeQuery = true)
    List<Object[]> findExclusionReasons(@Param("orgId") UUID orgId,
            @Param("namedSources") Collection<String> namedSources,
            @Param("namedVersions") Collection<String> namedVersions,
            @Param("provenanceSources") Collection<String> provenanceSources,
            @Param("textVersionSources") Collection<String> textVersionSources,
            @Param("personSources") Collection<String> personSources,
            @Param("softOptInBases") Collection<String> softOptInBases,
            @Param("cutoffAt") Instant cutoffAt,
            @Param("cutoffDate") LocalDate cutoffDate,
            @Param("ids") Collection<UUID> ids);

    /** Ids of one member's granting email consents that ConsentGate does not accept as proof. */
    @Query(value = ConsentGateSql.UNPROVEN_GRANT_IDS, nativeQuery = true)
    List<Object> findUnprovenGrantIds(@Param("orgId") UUID orgId,
            @Param("membershipId") UUID membershipId,
            @Param("namedSources") Collection<String> namedSources,
            @Param("namedVersions") Collection<String> namedVersions,
            @Param("provenanceSources") Collection<String> provenanceSources,
            @Param("textVersionSources") Collection<String> textVersionSources,
            @Param("softOptInBases") Collection<String> softOptInBases);

    // ---- Audience read model ----

    List<FanFeature> findByMembershipIdIn(Collection<UUID> membershipIds);

    /** Rows of {@code [class, count]} over every member of the org; a member without a row counts as none. */
    @Query("""
            select coalesce(f.fanClass, 'none'), count(m)
              from Membership m left join FanFeature f on f.membershipId = m.membershipId
             where m.orgId = :orgId
             group by coalesce(f.fanClass, 'none')
            """)
    List<Object[]> countByClass(@Param("orgId") UUID orgId);

    /** First page of {@code [membershipId, taste]} rows, keyset-ordered by membership id. */
    @Query("""
            select f.membershipId, f.taste from FanFeature f
             where f.orgId = :orgId and f.taste is not null
             order by f.membershipId
            """)
    List<Object[]> findTastePage(@Param("orgId") UUID orgId, Limit limit);

    /** Next page of {@code [membershipId, taste]} rows after {@code after}. */
    @Query("""
            select f.membershipId, f.taste from FanFeature f
             where f.orgId = :orgId and f.taste is not null and f.membershipId > :after
             order by f.membershipId
            """)
    List<Object[]> findTastePageAfter(@Param("orgId") UUID orgId, @Param("after") UUID after, Limit limit);

    @Query("select count(f) from FanFeature f where f.orgId = :orgId and f.paidOrders >= :min")
    long countWithPaidOrdersAtLeast(@Param("orgId") UUID orgId, @Param("min") int min);
}
