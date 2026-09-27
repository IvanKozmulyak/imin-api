package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.SurveyResponse;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface SurveyResponseRepository extends Repository<SurveyResponse, UUID> {

    SurveyResponse save(SurveyResponse response);

    long countByEventId(UUID eventId);

    long countByCreatedAtBefore(Instant cutoff);

    @Modifying
    @Transactional
    @Query("delete from SurveyResponse s where s.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
