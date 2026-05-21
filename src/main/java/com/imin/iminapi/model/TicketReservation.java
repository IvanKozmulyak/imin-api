package com.imin.iminapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per checkout-time inventory hold. Replaces the previous "reserved
 * is a sum on the tier" model; the {@code reserved} counter on
 * {@link TicketTier} is now derived bookkeeping driven by inserts/updates here.
 *
 * <p>Lifecycle: {@link ReservationStatus#HELD} on create →
 * {@link ReservationStatus#CONFIRMED} on payment success, or
 * {@link ReservationStatus#RELEASED} on webhook expire/fail, sweeper timeout,
 * or Stripe session-create failure. Terminal states are sticky — the sweeper
 * and webhook handlers all check status before transitioning so a replay is a
 * no-op.
 *
 * <p>{@code expires_at} is the wall-clock instant past which the
 * {@code ReservationSweeper} releases a still-HELD row. For paid flows it
 * mirrors the Stripe session's {@code expires_at}; for free flows it's a
 * short fallback (the row transitions to CONFIRMED in the same transaction
 * so the sweeper should never see it).
 */
@Entity
@Table(name = "ticket_reservations")
@Getter
@Setter
public class TicketReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tier_id", nullable = false)
    private UUID tierId;

    @Column(nullable = false)
    private int qty;

    /** Null for free checkouts. Unique-indexed for the not-null subset. */
    @Column(name = "stripe_session_id", length = 255)
    private String stripeSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status = ReservationStatus.HELD;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "release_reason", length = 32)
    private String releaseReason;
}
