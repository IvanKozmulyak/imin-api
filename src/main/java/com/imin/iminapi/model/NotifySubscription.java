package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A buyer's request to be notified when tickets are released for a given event.
 *
 * Captured by {@code POST /api/v1/public/events/{id}/notify}. The (event_id, email)
 * pair is unique per subscription; email is lowercased server-side before insert so
 * case-variants of the same address don't double-subscribe. Email sending is not yet
 * wired — this table is the durable record for a later notification job.
 */
@Entity
@Table(name = "notify_subscriptions")
@Getter
@Setter
public class NotifySubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
    }
}
