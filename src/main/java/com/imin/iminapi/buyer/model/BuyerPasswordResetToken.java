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
 * Buyer password-reset token — {@code password_reset_tokens} (V13:26-33) with
 * the FK repointed at {@code buyer_accounts}. Hashed at rest, single-use via
 * {@link #consumedAt}, 30-minute TTL set by the R1.2 service.
 *
 * <p>R1.1 only sweeps expired rows.
 */
@Entity
@Table(name = "buyer_password_reset_tokens")
@Getter
@Setter
public class BuyerPasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "buyer_account_id", nullable = false)
    private UUID buyerAccountId;

    @NotNull
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        if (createdAt == null) createdAt = Times.nowMicros();
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        if (expiresAt != null) expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (consumedAt != null) consumedAt = consumedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
