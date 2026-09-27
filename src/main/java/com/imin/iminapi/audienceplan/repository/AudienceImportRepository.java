package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.AudienceImport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AudienceImportRepository extends Repository<AudienceImport, UUID> {

    AudienceImport save(AudienceImport audienceImport);

    Optional<AudienceImport> findById(UUID id);

    List<AudienceImport> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    List<AudienceImport> findByOrgIdOrderByCreatedAtDescIdDesc(UUID orgId, Pageable page);
}
