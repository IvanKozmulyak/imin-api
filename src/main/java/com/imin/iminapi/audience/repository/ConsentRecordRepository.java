package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.ConsentRecord;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped via the membership_id → membership.org_id join (M4).
 * Append-only: no delete/update methods exposed.
 */
@RepositoryRestResource(exported = false)
public interface ConsentRecordRepository extends Repository<ConsentRecord, UUID> {

    ConsentRecord save(ConsentRecord record);

    /** Distinct members who gave an email consent at this event's door. */
    @Query("select count(distinct c.membershipId) from ConsentRecord c where c.eventId = :eventId"
            + " and c.source = :source and c.channel = 'email' and c.status = 'subscribed'")
    long countMembersByEventAndSource(@Param("eventId") UUID eventId, @Param("source") String source);

    /** Fetch all consent records for a membership, chronological (for DSAR access + UI). */
    @Query("select c from ConsentRecord c where c.membershipId = :membershipId order by c.occurredAt asc")
    List<ConsentRecord> findByMembershipId(@Param("membershipId") UUID membershipId);

    @Query("select c from ConsentRecord c where c.membershipId in :membershipIds")
    List<ConsentRecord> findByMembershipIdIn(@Param("membershipIds") Collection<UUID> membershipIds);

    /**
     * Count unsubscribes across org (for unsubscribe rate metric).
     * Joins through membership.
     */
    @Query("""
            select count(c) from ConsentRecord c
             join Membership m on m.membershipId = c.membershipId
             where m.orgId = :orgId and c.status = 'unsubscribed'
            """)
    long countUnsubsByOrgId(@Param("orgId") UUID orgId);
}
