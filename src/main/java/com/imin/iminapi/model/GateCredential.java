package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One row per organization that has provisioned a gate-scanner password.
 * {@code orgId} is both PK and FK — there's at most one credential per org.
 * The credential is rotated by the organizer dashboard via
 * {@code POST /api/v1/orgs/{orgId}/gate/credentials/rotate}, which upserts
 * this row (insert if absent, overwrite hash + bump {@code lastRotatedAt}).
 */
@Entity
@Table(name = "gate_credentials")
@Getter
@Setter
public class GateCredential {

    @Id
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "last_rotated_at", nullable = false)
    private Instant lastRotatedAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncate() {
        if (lastRotatedAt != null) lastRotatedAt = lastRotatedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
