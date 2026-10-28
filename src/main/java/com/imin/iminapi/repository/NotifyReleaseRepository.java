package com.imin.iminapi.repository;

import com.imin.iminapi.model.NotifyRelease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Release rows of the notify-me list (V201). Single writer: the {@code NotifyReleaseSender} sweep. */
@RepositoryRestResource(exported = false)
public interface NotifyReleaseRepository extends JpaRepository<NotifyRelease, UUID> {

    /** Adds {@code sent} emails to the event's open release, opening one first when none is open. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO notify_releases (id, event_id, first_sent_at, last_sent_at, sent_count) "
            + "VALUES (:id, :eventId, :at, :at, :sent) "
            + "ON CONFLICT (event_id) WHERE closed_at IS NULL DO UPDATE "
            + "SET sent_count = notify_releases.sent_count + EXCLUDED.sent_count, "
            + "last_sent_at = EXCLUDED.last_sent_at",
            nativeQuery = true)
    int recordSends(@Param("id") UUID id, @Param("eventId") UUID eventId,
                    @Param("at") Instant at, @Param("sent") int sent);

    /** Ends the event's open release; the next email starts a new row. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE notify_releases SET closed_at = :at WHERE event_id = :eventId AND closed_at IS NULL",
            nativeQuery = true)
    int closeOpen(@Param("eventId") UUID eventId, @Param("at") Instant at);

    /** The event's latest release, open or closed. */
    Optional<NotifyRelease> findTopByEventIdOrderByFirstSentAtDesc(UUID eventId);
}
