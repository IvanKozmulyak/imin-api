package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.PublishInvite;
import com.imin.iminapi.model.EventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface PublishInviteRepository extends Repository<PublishInvite, UUID> {

    PublishInvite save(PublishInvite invite);

    Optional<PublishInvite> findByEventIdAndOrgId(UUID eventId, UUID orgId);

    Optional<PublishInvite> findById(UUID eventId);

    @Modifying
    @Query("delete from PublishInvite p where p.eventId = :eventId")
    int deleteByEventId(@Param("eventId") UUID eventId);

    /** 1 when this caller took an unclaimed intent. */
    @Modifying
    @Query("""
            update PublishInvite p set p.claimedAt = :now, p.attempts = p.attempts + 1
             where p.eventId = :eventId and p.claimedAt is null""")
    int claim(@Param("eventId") UUID eventId, @Param("now") Instant now);

    /** 1 when this caller took over the claim it read as {@code was}; another sweeper got it first otherwise. */
    @Modifying
    @Query("""
            update PublishInvite p set p.claimedAt = :now, p.attempts = p.attempts + 1
             where p.eventId = :eventId and p.claimedAt = :was""")
    int reclaim(@Param("eventId") UUID eventId, @Param("was") Instant was, @Param("now") Instant now);

    /** Deletes the intent only while the run's own claim is on it, so a newer intent saved meanwhile survives. */
    @Modifying
    @Query("delete from PublishInvite p where p.eventId = :eventId and p.claimedAt = :claimedAt")
    int complete(@Param("eventId") UUID eventId, @Param("claimedAt") Instant claimedAt);

    /**
     * Intents of events in {@code status} whose run never completed: claimed before {@code cutoff}, or never claimed
     * although the event was published between {@code expiry} and {@code cutoff}. Oldest first.
     */
    @Query("""
            select p from PublishInvite p, Event e
             where e.id = p.eventId
               and e.status = :status
               and ((p.claimedAt is not null and p.claimedAt < :cutoff)
                 or (p.claimedAt is null and coalesce(e.publishedAt, p.updatedAt) < :cutoff
                     and coalesce(e.publishedAt, p.updatedAt) >= :expiry))
             order by p.updatedAt asc, p.eventId asc""")
    List<PublishInvite> findStale(@Param("status") EventStatus status, @Param("cutoff") Instant cutoff,
                                  @Param("expiry") Instant expiry, Pageable page);

    /** Never-claimed intents of events in {@code status} published before {@code expiry}. */
    @Query("""
            select p.eventId from PublishInvite p, Event e
             where e.id = p.eventId
               and e.status = :status
               and p.claimedAt is null
               and coalesce(e.publishedAt, p.updatedAt) < :expiry
             order by p.eventId asc""")
    List<UUID> findExpiredUnclaimed(@Param("status") EventStatus status, @Param("expiry") Instant expiry,
                                    Pageable page);

    @Modifying
    @Query("delete from PublishInvite p where p.eventId = :eventId and p.claimedAt is null")
    int deleteUnclaimed(@Param("eventId") UUID eventId);

    /** Claimed intents of events in {@code statuses} whose claim is older than {@code before}. */
    @Modifying
    @Query("""
            delete from PublishInvite p
             where p.claimedAt is not null and p.claimedAt < :before
               and p.eventId in (select e.id from Event e where e.status in :statuses)""")
    int deleteClaimedOf(@Param("statuses") List<EventStatus> statuses, @Param("before") Instant before);
}
