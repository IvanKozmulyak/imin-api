package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One row per attendee. {@code token} is the URL-safe public lookup key for
 * {@code /tickets/{token}}; {@code tier_name} is snapshotted at issue time
 * so the display string keeps working even if the organizer later deletes
 * or renames the tier.
 */
@Entity
@Table(name = "tickets")
@Getter
@Setter
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "tier_id", nullable = false)
    private UUID tierId;

    @Column(name = "tier_name", nullable = false, length = 128)
    private String tierName;

    /** "issued" | "redeemed" | "revoked". Legacy rows may carry "pre" (synonym for "issued"). */
    @Column(nullable = false, length = 16)
    private String state = "issued";

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "redeemed_by_user_id")
    private UUID redeemedByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
    }
}
