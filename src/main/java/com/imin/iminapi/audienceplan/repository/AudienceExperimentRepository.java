package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AudienceExperimentRepository extends Repository<AudienceExperiment, UUID> {

    AudienceExperiment save(AudienceExperiment experiment);

    Optional<AudienceExperiment> findById(UUID id);

    List<AudienceExperiment> findByOrgIdAndEventId(UUID orgId, UUID eventId);

    /** The arm a draft campaign was created for, if it is an invitation arm. */
    Optional<AudienceExperiment> findFirstByCampaignIdAndOrgId(UUID campaignId, UUID orgId);

    /** Approved arms of one kind waiting for a trigger (slump). */
    List<AudienceExperiment> findByOrgIdAndEventIdAndArmAndArmedAtIsNotNull(UUID orgId, UUID eventId, String arm);

    /** Arms one experiment, only while it is unarmed; 0 rows means it already was. Writes {@code armed_at} alone. */
    @Modifying
    @Query("update AudienceExperiment e set e.armedAt = :at where e.id = :id and e.armedAt is null")
    int arm(@Param("id") UUID id, @Param("at") java.time.Instant at);

    /** Clears the arming of a campaign's experiment, only if it is armed. Writes {@code armed_at} alone. */
    @Modifying
    @Query("update AudienceExperiment e set e.armedAt = null"
            + " where e.campaignId = :campaignId and e.orgId = :orgId and e.armedAt is not null")
    int disarm(@Param("campaignId") UUID campaignId, @Param("orgId") UUID orgId);

    /** The stored arming, read fresh rather than from a loaded entity. */
    boolean existsByIdAndArmedAtIsNotNull(UUID id);

    /** This event's experiments for one class × genre fit, across every plan generation, oldest first. */
    @Query("""
            select e from AudienceExperiment e, AudiencePlanSegment s
             where s.id = e.planSegmentId
               and e.orgId = :orgId
               and e.eventId = :eventId
               and s.classKey = :classKey
               and s.genreFit = :genreFit
             order by e.createdAt asc, e.id asc
            """)
    List<AudienceExperiment> findInvited(@Param("orgId") UUID orgId, @Param("eventId") UUID eventId,
                                         @Param("classKey") String classKey, @Param("genreFit") String genreFit);
}
