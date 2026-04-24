package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "auth_sessions")
@Getter
@Setter
public class AuthSession {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Times.nowMicros();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt = Times.nowMicros();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        issuedAt = issuedAt == null ? Times.nowMicros() : issuedAt.truncatedTo(ChronoUnit.MICROS);
        lastUsedAt = lastUsedAt == null ? Times.nowMicros() : lastUsedAt.truncatedTo(ChronoUnit.MICROS);
        if (expiresAt != null) expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (revokedAt != null) revokedAt = revokedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
