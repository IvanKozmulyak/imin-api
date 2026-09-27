package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.AudiencePlanSegment;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AudiencePlanSegmentRepository extends Repository<AudiencePlanSegment, UUID> {

    AudiencePlanSegment save(AudiencePlanSegment segment);

    List<AudiencePlanSegment> findByPlanIdOrderByPositionAsc(UUID planId);
}
