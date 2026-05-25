package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A bearer-token session issued to a gate device after a successful
 * {@code POST /api/v1/gate/login}. Mirrors {@link AuthSession} (the user-side
 * counterpart) but is keyed by {@code orgId} instead of {@code userId} —
 * gate phones are intentionally not tied to a real human identity.
 */
@Entity
@Table(name = "gate_sessions")
@Getter
@Setter
public class GateSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Times.nowMicros();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @PrePersist
    @PreUpdate
    void truncate() {
        issuedAt = issuedAt == null ? Times.nowMicros() : issuedAt.truncatedTo(ChronoUnit.MICROS);
        if (expiresAt != null) expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (revokedAt != null) revokedAt = revokedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
