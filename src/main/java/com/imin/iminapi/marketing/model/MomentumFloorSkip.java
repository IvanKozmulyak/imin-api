package com.imin.iminapi.marketing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The latest Momentum moment of an event that the hourly pass skipped because fewer than {@code floor} people can
 * get the email (V204). Written and cleared only by {@code MomentumEvaluator}, through the repository's upsert.
 */
@Entity
@Table(name = "momentum_floor_skips")
@Getter
@Setter
public class MomentumFloorSkip {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "trigger_type", nullable = false, length = 24)
    private String triggerType;

    @Column(nullable = false)
    private int sendable;

    @Column(nullable = false)
    private int floor;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        if (checkedAt != null) checkedAt = checkedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
