package com.imin.iminapi.audience.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Segment definition.
 * kind='dynamic'  rules_json evaluated live per-query.
 * kind='static'   snapshot_ids frozen at snapshot time; re-evaluation returns the snapshot.
 * prebuilt=true   provisioned by the system (7 prebuilt segments); UI shows no delete button.
 */
@Entity
@Table(name = "segments")
@Getter
@Setter
public class Segment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 128)
    private String name;

    /** dynamic | static */
    @Column(nullable = false, length = 8)
    private String kind = "dynamic";

    @Column(nullable = false)
    private boolean prebuilt = false;

    /** JSON array of {field, operator, value} */
    @Column(name = "rules_json", columnDefinition = "TEXT")
    private String rulesJson;

    /** JSON array of UUID strings — set when kind=static via snapshot */
    @Column(name = "snapshot_ids", columnDefinition = "TEXT")
    private String snapshotIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
