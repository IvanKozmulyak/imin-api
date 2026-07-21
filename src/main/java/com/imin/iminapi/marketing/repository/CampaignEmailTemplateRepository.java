package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.CampaignEmailTemplate;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped store for org-saved {@link CampaignEmailTemplate} rows (V66).
 * Every finder takes orgId — a template only resolves inside its own org (SPINE INVARIANT).
 */
// Internal store — never exposed over Spring Data REST (auto-export leaks the table
// into the public OpenAPI and can crash springdoc on ambiguous search mappings).
@RepositoryRestResource(exported = false)
public interface CampaignEmailTemplateRepository extends Repository<CampaignEmailTemplate, UUID> {

    CampaignEmailTemplate save(CampaignEmailTemplate template);

    /** Org-scoped by-id load — another org's (or a missing) template is an empty Optional. */
    Optional<CampaignEmailTemplate> findByIdAndOrgId(UUID id, UUID orgId);

    /** The org's saved templates, newest first (the composer picker lists these after builtins). */
    List<CampaignEmailTemplate> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    void delete(CampaignEmailTemplate template);
}
