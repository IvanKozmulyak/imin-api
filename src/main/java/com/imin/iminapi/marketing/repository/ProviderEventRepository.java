package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.ProviderEvent;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface ProviderEventRepository extends Repository<ProviderEvent, UUID> {

    ProviderEvent save(ProviderEvent e);

    @Query("select count(pe) from ProviderEvent pe where pe.campaignId = :campaignId and pe.type = :type")
    long countByCampaignIdAndType(@Param("campaignId") UUID campaignId, @Param("type") String type);

    /** The campaign's events of one type received at or after {@code since}: the breaker's per-campaign window. */
    @Query("select count(pe) from ProviderEvent pe where pe.campaignId = :campaignId and pe.type = :type"
            + " and pe.createdAt >= :since")
    long countByCampaignIdAndTypeSince(@Param("campaignId") UUID campaignId, @Param("type") String type,
                                       @Param("since") java.time.Instant since);
}
