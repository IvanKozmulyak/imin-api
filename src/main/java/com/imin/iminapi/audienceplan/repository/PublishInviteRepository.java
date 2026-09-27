package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.PublishInvite;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface PublishInviteRepository extends Repository<PublishInvite, UUID> {

    PublishInvite save(PublishInvite invite);

    Optional<PublishInvite> findByEventIdAndOrgId(UUID eventId, UUID orgId);

    Optional<PublishInvite> findById(UUID eventId);

    /** 1 when this caller removed the row, 0 when it was already gone; publish uses it to claim the intent. */
    @Modifying
    @Query("delete from PublishInvite p where p.eventId = :eventId")
    int deleteByEventId(@Param("eventId") UUID eventId);
}
