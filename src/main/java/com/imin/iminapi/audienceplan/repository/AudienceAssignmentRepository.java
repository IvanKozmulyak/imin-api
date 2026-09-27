package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.AudienceAssignment;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AudienceAssignmentRepository extends Repository<AudienceAssignment, AudienceAssignment.Key> {

    AudienceAssignment save(AudienceAssignment assignment);

    List<AudienceAssignment> findByMembershipId(UUID membershipId);

    /** The given members held out of this org's experiments for the event. */
    @Query("""
            select distinct a.membershipId from AudienceAssignment a, AudienceExperiment e
             where e.id = a.experimentId
               and e.orgId = :orgId
               and e.eventId = :eventId
               and a.arm = 'holdout'
               and a.membershipId in :membershipIds
            """)
    List<UUID> findHeldOut(@Param("orgId") UUID orgId, @Param("eventId") UUID eventId,
                           @Param("membershipIds") Collection<UUID> membershipIds);

    @Modifying
    @Transactional
    @Query("delete from AudienceAssignment a where a.membershipId = :membershipId")
    int deleteByMembershipId(@Param("membershipId") UUID membershipId);
}
