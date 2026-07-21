package com.imin.iminapi.marketing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per accepted provider webhook (Resend today). Append-only. The
 * UNIQUE(provider, provider_event_id) makes replays idempotent; the row is
 * INSERTed via {@code ProviderEventDedupService} (JDBC) so a duplicate-key
 * violation stays recoverable inside the surrounding transaction.
 */
@Entity
@Table(name = "provider_events")
@Getter
@Setter
public class ProviderEvent {

    public static final String PROVIDER_RESEND = "resend";
    /** Bird (MessageBird) SMS — inbound STOP replies + delivery receipts. */
    public static final String PROVIDER_BIRD = "bird";

    // Resend event types we project on (spec §2.5 step 5).
    public static final String TYPE_DELIVERED = "email.delivered";
    public static final String TYPE_BOUNCED = "email.bounced";
    public static final String TYPE_COMPLAINED = "email.complained";
    public static final String TYPE_OPENED = "email.opened";
    public static final String TYPE_CLICKED = "email.clicked";

    @Id
    private UUID id;

    @Column(nullable = false, length = 16)
    private String provider;

    @Column(name = "provider_event_id", nullable = false, length = 128)
    private String providerEventId;

    @Column(name = "provider_message_id", length = 64)
    private String providerMessageId;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "recipient_id")
    private UUID recipientId;

    @Column(length = 64)
    private String type;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
