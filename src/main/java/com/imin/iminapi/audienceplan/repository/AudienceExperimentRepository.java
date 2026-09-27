package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AudienceExperimentRepository extends Repository<AudienceExperiment, UUID> {

    AudienceExperiment save(AudienceExperiment experiment);

    Optional<AudienceExperiment> findById(UUID id);

    List<AudienceExperiment> findByOrgIdAndEventId(UUID orgId, UUID eventId);
}
