package com.imin.iminapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One release of an event's notify-me list (V201): the release emails the provider accepted
 * while the event stayed purchasable. Written only by {@code NotifyReleaseSender} through
 * {@code NotifyReleaseRepository}'s upsert and close; read for the organizer's "last email".
 */
@Entity
@Table(name = "notify_releases")
@Getter
public class NotifyRelease {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /** When the first email of this release was accepted. */
    @Column(name = "first_sent_at", nullable = false, updatable = false)
    private Instant firstSentAt;

    /** When the latest email of this release was accepted; the organizer's "last email" time. */
    @Column(name = "last_sent_at", nullable = false, updatable = false)
    private Instant lastSentAt;

    /** Emails accepted during this release, retries included; suppressed and holder skips never count. */
    @Column(name = "sent_count", nullable = false, updatable = false)
    private int sentCount;

    /** Set when a sweep finds the event with nothing purchasable; null while the release is open. */
    @Column(name = "closed_at", updatable = false)
    private Instant closedAt;
}
