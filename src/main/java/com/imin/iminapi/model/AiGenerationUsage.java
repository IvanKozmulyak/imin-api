package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One row per AI-generation ATTEMPT, written by {@code AiQuotaService} before the paid
 * provider is called. Backs the per-user rolling-24h anti-abuse quota (see V64). {@code kind}
 * is {@code "image"} or {@code "text"} (see {@code AiQuotaKind}).
 */
@Entity
@Table(name = "ai_generation_usage")
@Getter
@Setter
public class AiGenerationUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
    }
}
