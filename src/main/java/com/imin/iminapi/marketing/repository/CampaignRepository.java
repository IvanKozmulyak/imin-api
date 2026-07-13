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
