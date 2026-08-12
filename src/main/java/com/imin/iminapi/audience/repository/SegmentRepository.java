package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.Segment;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link Segment} (M4).
 * Every read takes orgId.
 */
@RepositoryRestResource(exported = false)
public interface SegmentRepository extends Repository<Segment, UUID> {

    Segment save(Segment segment);

    @Query("select s from Segment s where s.id = :id and s.orgId = :orgId")
    Optional<Segment> findByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query("select s from Segment s where s.orgId = :orgId order by s.createdAt desc")
    List<Segment> findByOrgId(@Param("orgId") UUID orgId);

    @Modifying
    @Transactional
    @Query("delete from Segment s where s.id = :id and s.orgId = :orgId and s.prebuilt = false")
    int deleteByIdAndOrgIdAndNotPrebuilt(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query("select count(s) > 0 from Segment s where s.orgId = :orgId and s.prebuilt = true")
    boolean hasPrebuiltSegments(@Param("orgId") UUID orgId);
}
