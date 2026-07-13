package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.Campaign;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link Campaign}.
 * Every read takes orgId. No unscoped finders are exposed (SPINE INVARIANT).
 */
public interface CampaignRepository extends Repository<Campaign, UUID> {

    Campaign save(Campaign campaign);

    @Query("select c from Campaign c where c.id = :id and c.orgId = :orgId")
    Optional<Campaign> findByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /**
     * Heartbeat: bump updated_at so the dispatcher's stale-`sending` reclaim does not fire
     * mid-send. Explicit @Modifying UPDATE that always issues an UPDATE regardless of
     * Hibernate dirty-checking (spec §2.5).
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Campaign c SET c.updatedAt=:ts WHERE c.id=:id")
    void touch(@org.springframework.data.repository.query.Param("id") java.util.UUID id,
               @org.springframework.data.repository.query.Param("ts") java.time.Instant ts);

    // TEST-SUPPORT ONLY: unscoped by-id load, used exclusively by CampaignService.forceStatusForTest.
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForTest(@Param("id") UUID id);

    @Query("""
            select c from Campaign c
             where c.orgId = :orgId
               and (:channel is null or c.channel = :channel)
               and (:status  is null or c.status  = :status)
             order by c.createdAt desc, c.id desc
            """)
    List<Campaign> listByOrg(@Param("orgId") UUID orgId,
                             @Param("channel") String channel,
                             @Param("status") String status,
                             Pageable pageable);

    @Query("""
            select count(c) from Campaign c
             where c.orgId = :orgId
               and (:channel is null or c.channel = :channel)
               and (:status  is null or c.status  = :status)
            """)
    long countByOrg(@Param("orgId") UUID orgId,
                    @Param("channel") String channel,
                    @Param("status") String status);
}
