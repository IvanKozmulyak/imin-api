package com.imin.iminapi.buyer.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * An opaque, server-side, revocable buyer session — the row behind the
 * {@code imin_buyer_session} cookie. Same shape as
 * {@link com.imin.iminapi.model.AuthSession}, different table and a different
 * FK, because sharing the session table would mean sharing {@code users}
 * (§2.1).
 *
 * <p>Lifetime is 180 days absolute and 90 days idle (§2.5): the failure mode of
 * a short session — being asked to sign in again while queuing outside a venue
 * with no signal — is the worst thing this feature can do, and the length costs
 * nothing {@link #revokedAt} cannot undo.
 */
@Entity
@Table(name = "buyer_sessions")
@Getter
@Setter
public class BuyerSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "buyer_account_id", nullable = false)
    private UUID buyerAccountId;

    /** SHA-256 hex of the raw cookie value. The raw token is never persisted. */
    @NotNull
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @NotNull
    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Times.nowMicros();

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @NotNull
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
