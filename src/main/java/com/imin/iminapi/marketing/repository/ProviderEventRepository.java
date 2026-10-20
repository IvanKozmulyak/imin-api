package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.ProviderEvent;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface ProviderEventRepository extends Repository<ProviderEvent, UUID> {

    ProviderEvent save(ProviderEvent e);

    @Query("select count(pe) from ProviderEvent pe where pe.campaignId = :campaignId and pe.type = :type")
    long countByCampaignIdAndType(@Param("campaignId") UUID campaignId, @Param("type") String type);

    /** Webhooks of these types received since {@code since}; the tracking guard reads opens and clicks. */
    @Query("select count(pe) from ProviderEvent pe where pe.provider = :provider and pe.type in :types"
            + " and pe.createdAt >= :since")
    long countByProviderAndTypesSince(@Param("provider") String provider,
                                      @Param("types") Collection<String> types,
                                      @Param("since") Instant since);
}
