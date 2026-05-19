package com.imin.iminapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit row for /api/v1/public/orders/recover. Inserted on every call
 * regardless of outcome so the rate-limit counters work.
 */
@Entity
@Table(name = "order_recovery_attempts")
@Getter
@Setter
public class OrderRecoveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "ip_hash", nullable = false, length = 64)
    private String ipHash;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt = Instant.now();
}
