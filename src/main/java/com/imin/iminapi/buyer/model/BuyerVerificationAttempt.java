package com.imin.iminapi.buyer.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One row per address-verification attempt, successful or not. This is the
 * DB-counted lockout of §2.2 (10 failures for one address inside 60 minutes
 * locks that address for 60 minutes) and it exists because
 * {@code RateLimitConfig} is {@code @Profile("!test")} — a security control the
 * test suite cannot assert on is a security control that will regress.
 *
 * <p>Written by R1.2; R1.1 sweeps rows older than 24 hours.
 */
@Entity
@Table(name = "buyer_verification_attempts")
@Getter
@Setter
public class BuyerVerificationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "email_normalized", nullable = false, length = 254)
    private String emailNormalized;

    @NotNull
    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt = Times.nowMicros();

    @Column(name = "succeeded", nullable = false)
    private boolean succeeded;

    /**
     * The caller who made this attempt (V121). Half of the lockout key: keyed
     * on the address alone, the counter was something a stranger could spend on
     * the owner's behalf. Never null in practice — the recorder substitutes a
     * sentinel — because an equality lookup on a NULL parameter matches nothing
     * and, on Postgres, does not even type-check reliably.
     */
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @PrePersist
    void truncateTimestamps() {
        if (attemptedAt == null) attemptedAt = Times.nowMicros();
        attemptedAt = attemptedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
