package com.imin.iminapi.marketing.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A Momentum Engine suggestion (spec §6.2). Trigger/status are stored as their
 * lowercase wire strings (VARCHAR columns) — the service converts to enums at
 * its boundary. metrics_snapshot / draft_payload are JSON stored as TEXT.
 */
@Entity
@Table(name = "momentum_suggestions")
@Getter
@Setter
public class MomentumSuggestion {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "trigger_type", nullable = false, length = 24)
    private String triggerType;

    @Column(nullable = false, length = 16)
    private String status = "suggested";

    @Column(name = "metrics_snapshot", nullable = false, columnDefinition = "TEXT")
    private String metricsSnapshot;

    @Column(name = "draft_payload", nullable = false, columnDefinition = "TEXT")
    private String draftPayload;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "suggested_at", nullable = false)
    private Instant suggestedAt = Times.nowMicros();

    @Column(name = "acted_at")
    private Instant actedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        if (suggestedAt != null) suggestedAt = suggestedAt.truncatedTo(ChronoUnit.MICROS);
        if (createdAt == null) createdAt = Times.nowMicros();
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        if (actedAt != null) actedAt = actedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
