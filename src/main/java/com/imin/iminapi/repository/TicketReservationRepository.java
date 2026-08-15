package com.imin.iminapi.repository;

import com.imin.iminapi.model.TicketReservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface TicketReservationRepository extends JpaRepository<TicketReservation, UUID> {

    /**
     * Sweeper feed: HELD rows whose {@code expires_at} is in the past. The DB-side
     * partial index {@code ix_ticket_reservations_status_expires} makes this O(log n)
     * even when CONFIRMED/RELEASED rows dominate the table.
     */
    @Query("""
        SELECT r FROM TicketReservation r
         WHERE r.status = com.imin.iminapi.model.ReservationStatus.HELD
           AND r.expiresAt < :now
         ORDER BY r.expiresAt ASC
    """)
    List<TicketReservation> findHeldExpiredBefore(@Param("now") Instant now, Pageable page);

    /**
     * Resolve a webhook payload's {@code session.id} to its originating reservation.
     * Used as the fallback when the metadata-stamped {@code reservation_id} is missing
     * (legacy event from before the metadata-passthrough deploy).
     */
    Optional<TicketReservation> findByStripeSessionId(String stripeSessionId);

    /**
     * Resolve a repeated {@code Idempotency-Key} to the hold the first call
     * took, so a native retry replays instead of reserving again.
     *
     * <p>Callers must never pass null: a null argument would match the whole
     * unkeyed population (every web checkout ever made) in insertion order.
     * {@code StripePaymentIntentService} normalises a blank key to null and
     * skips this lookup entirely.
     */
    Optional<TicketReservation> findByIdempotencyKey(String idempotencyKey);

    /**
     * Atomic conditional transition HELD → RELEASED. Returns row count: 1 means
     * we won the race against a concurrent releaser / sweeper / webhook; 0 means
     * the row was already non-HELD (or unknown) and our caller should no-op.
     *
     * <p>{@code clearAutomatically=true} drops any stale {@link TicketReservation}
     * entity from the session so a subsequent {@code findById} re-reads from the DB
     * and sees the updated status.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE TicketReservation r
           SET r.status = com.imin.iminapi.model.ReservationStatus.RELEASED,
               r.releasedAt = :now,
               r.releaseReason = :reason
         WHERE r.id = :id
           AND r.status = com.imin.iminapi.model.ReservationStatus.HELD
    """)
    int markReleased(@Param("id") UUID id, @Param("now") Instant now, @Param("reason") String reason);

    /** Atomic conditional transition HELD → CONFIRMED, same race semantics as {@link #markReleased}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE TicketReservation r
           SET r.status = com.imin.iminapi.model.ReservationStatus.CONFIRMED,
               r.confirmedAt = :now
         WHERE r.id = :id
           AND r.status = com.imin.iminapi.model.ReservationStatus.HELD
    """)
    int markConfirmed(@Param("id") UUID id, @Param("now") Instant now);
}
