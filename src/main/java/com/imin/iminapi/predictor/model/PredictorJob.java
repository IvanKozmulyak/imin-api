package com.imin.iminapi.predictor.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A queued background unit of predictor work (V162). */
@Entity
@Table(name = "predictor_job")
@Getter
@Setter
public class PredictorJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String kind;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson = "{}";

    /** queued | running | done | failed. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "run_after", nullable = false)
    private Instant runAfter;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPersist() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt == null ? Times.nowMicros() : updatedAt.truncatedTo(ChronoUnit.MICROS);
        runAfter = runAfter == null ? createdAt : runAfter.truncatedTo(ChronoUnit.MICROS);
        if (lockedUntil != null) lockedUntil = lockedUntil.truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Times.nowMicros();
        if (runAfter != null) runAfter = runAfter.truncatedTo(ChronoUnit.MICROS);
        if (lockedUntil != null) lockedUntil = lockedUntil.truncatedTo(ChronoUnit.MICROS);
    }
}
