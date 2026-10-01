package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.ConsentRecord;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped via the membership_id → membership.org_id join (M4).
 * Append-only: no delete methods; the one update is {@link #markConfirmed}, which only sets confirmed_at.
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

    /** Email grants of this member still waiting for their address to be confirmed, recorded up to {@code upTo}. */
    @Query("select c from ConsentRecord c where c.membershipId = :membershipId and c.channel = 'email'"
            + " and c.status = 'subscribed' and c.confirmationRequired = true and c.confirmedAt is null"
            + " and c.occurredAt <= :upTo order by c.occurredAt asc")
    List<ConsentRecord> findAwaitingConfirmation(@Param("membershipId") UUID membershipId,
                                                 @Param("upTo") Instant upTo);

    /** Sets confirmed_at on a pending record; the only in-place change a consent record ever takes. */
    @Modifying(flushAutomatically = true)
    @Query("update ConsentRecord c set c.confirmedAt = :at where c.id = :id"
            + " and c.confirmationRequired = true and c.confirmedAt is null")
    int markConfirmed(@Param("id") UUID id, @Param("at") Instant at);
}
