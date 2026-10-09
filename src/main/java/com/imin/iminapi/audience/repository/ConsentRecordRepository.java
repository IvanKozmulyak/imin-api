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

    /**
     * Whether this member already holds the checkout email grant of this order. Rows before V132 have no
     * order_id and end their proof_text with ", order <id>", which is how every checkout capture wrote it.
     */
    @Query("select count(c) > 0 from ConsentRecord c where c.membershipId = :membershipId and c.channel = 'email'"
            + " and c.source = 'checkout' and c.status = 'subscribed'"
            + " and (c.orderId = :orderId or (c.orderId is null and c.proofText like :legacyProofSuffix))")
    boolean existsCheckoutGrant(@Param("membershipId") UUID membershipId, @Param("orderId") UUID orderId,
                                @Param("legacyProofSuffix") String legacyProofSuffix);

    /** Whether this member unsubscribed from email at or after {@code since}. */
    @Query("select count(c) > 0 from ConsentRecord c where c.membershipId = :membershipId and c.channel = 'email'"
            + " and c.status = 'unsubscribed' and c.occurredAt >= :since")
    boolean existsEmailUnsubscribeSince(@Param("membershipId") UUID membershipId, @Param("since") Instant since);

    /**
     * Whether the latest email record in force before {@code at} is an unsubscribe: grants still awaiting
     * confirmation then do not count, and on a tie the unsubscribe wins.
     */
    @Query(value = "select coalesce((select r.status = 'unsubscribed' from consent_records r"
            + " where r.membership_id = :membershipId and r.channel = 'email' and r.occurred_at < :at"
            + " and (r.status = 'unsubscribed' or r.confirmation_required = false or r.confirmed_at < :at)"
            + " order by r.occurred_at desc, (r.status = 'unsubscribed') desc limit 1), false)",
            nativeQuery = true)
    boolean latestEmailRecordBeforeIsUnsubscribe(@Param("membershipId") UUID membershipId, @Param("at") Instant at);

    /** Sets confirmed_at on a pending record; the only in-place change a consent record ever takes. */
    @Modifying(flushAutomatically = true)
    @Query("update ConsentRecord c set c.confirmedAt = :at where c.id = :id"
            + " and c.confirmationRequired = true and c.confirmedAt is null")
    int markConfirmed(@Param("id") UUID id, @Param("at") Instant at);
}
