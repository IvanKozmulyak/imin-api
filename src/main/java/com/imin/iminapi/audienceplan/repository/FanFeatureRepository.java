package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.FanFeature;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface FanFeatureRepository extends Repository<FanFeature, UUID> {

    FanFeature save(FanFeature feature);

    Optional<FanFeature> findById(UUID membershipId);

    @Modifying
    @Transactional
    @Query("delete from FanFeature f where f.membershipId = :membershipId")
    int deleteByMembershipId(@Param("membershipId") UUID membershipId);
}
