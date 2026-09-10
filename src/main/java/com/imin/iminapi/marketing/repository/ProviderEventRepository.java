package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.ProviderEvent;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface ProviderEventRepository extends Repository<ProviderEvent, UUID> {

    ProviderEvent save(ProviderEvent e);

    @Query("select count(pe) from ProviderEvent pe where pe.campaignId = :campaignId and pe.type = :type")
    long countByCampaignIdAndType(@Param("campaignId") UUID campaignId, @Param("type") String type);

    /**
     * Clears raw webhook bodies left by rows written before the write was
     * removed. The rows survive — each is an idempotency claim, and dropping one
     * would let a provider replay re-project.
     */
    @Transactional
    @Modifying
    @Query("update ProviderEvent pe set pe.payload = null where pe.payload is not null")
    int clearRetainedPayloads();
}
