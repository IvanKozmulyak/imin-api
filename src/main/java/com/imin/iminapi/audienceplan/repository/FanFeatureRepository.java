package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.FanFeature;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
}
