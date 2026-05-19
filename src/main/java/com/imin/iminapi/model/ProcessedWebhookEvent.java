package com.imin.iminapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Marker row for a Stripe webhook event id we've already processed. Inserted as
 * the first step of every dedup-routed handler. Duplicate-key INSERT means the
 * event was already handled — see {@code processed_webhook_events} migration
 * for the full contract.
 */
@Entity
@Table(name = "processed_webhook_events")
@Getter
@Setter
public class ProcessedWebhookEvent {

    @Id
    @Column(name = "stripe_event_id", nullable = false)
    private String stripeEventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();
}
