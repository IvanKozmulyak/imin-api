package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.ConsentRecord;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped via the membership_id → membership.org_id join (M4).
 * Append-only: no delete/update methods exposed.
 */
public interface ConsentRecordRepository extends Repository<ConsentRecord, UUID> {

    ConsentRecord save(ConsentRecord record);

    /** Fetch all consent records for a membership, chronological (for DSAR access + UI). */
    @Query("select c from ConsentRecord c where c.membershipId = :membershipId order by c.occurredAt asc")
    List<ConsentRecord> findByMembershipId(@Param("membershipId") UUID membershipId);

    /** Count of unsubscribe records for a membership — used for complaint rate metric. */
    @Query("select count(c) from ConsentRecord c where c.membershipId = :membershipId and c.status = 'unsubscribed'")
    long countUnsubsByMembershipId(@Param("membershipId") UUID membershipId);

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
