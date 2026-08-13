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
 * A six-digit address-verification code, stored peppered
 * ({@code HMAC-SHA256(IMIN_BUYER_CODE_SECRET, code)}) rather than as a bare
 * digest — a plain SHA-256 of a six-digit code is an offline dictionary of one
 * million entries (§2.2).
 *
 * <p>Written and consumed by R1.2; R1.1 only sweeps expired rows.
 */
@Entity
@Table(name = "buyer_email_verification_codes")
@Getter
@Setter
public class BuyerEmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "buyer_account_id", nullable = false)
    private UUID buyerAccountId;

    @NotNull
    @Column(name = "email_normalized", nullable = false, length = 254)
    private String emailNormalized;

    @NotNull
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

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
