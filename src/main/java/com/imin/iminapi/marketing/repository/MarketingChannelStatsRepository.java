package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.ProviderEvent;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

/**
 * Org-scoped provider-event counts behind GET /api/v1/marketing/channels.
 *
 * <p>Separate from {@link ProviderEventRepository} (which counts PER CAMPAIGN for the
 * complaint breaker) because the channels read-model needs the org-wide aggregate.
 * {@code provider_events} carries no {@code org_id}, so the org is reached by joining
 * through {@code campaigns} — the same implicit-join shape
 * {@code CampaignRecipientRepository#countRecentSendsForOrg} uses.
 *
 * <p>Internal store — never exposed over Spring Data REST. Auto-export both leaks the table
 * into the public OpenAPI and has previously crashed springdoc.
 */
@RepositoryRestResource(exported = false)
public interface MarketingChannelStatsRepository extends Repository<ProviderEvent, UUID> {

    /**
     * Count the org's provider events of one type, joining events to their campaign by org.
     * Backs the org-wide complaint rate (complaints ÷ delivered) on the channels surface.
     */
    @Query("""
            select count(pe) from ProviderEvent pe, com.imin.iminapi.marketing.model.Campaign c
             where pe.campaignId = c.id
               and c.orgId = :orgId
               and pe.type = :type
            """)
    long countByOrgIdAndType(@Param("orgId") UUID orgId, @Param("type") String type);
}
