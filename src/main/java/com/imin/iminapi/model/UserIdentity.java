package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A link between an imin {@link User} and an external OAuth/OIDC provider
 * identity (Google, Apple). One row per linked provider account. The
 * {@code (provider, providerUserId)} pair is unique across all users so a
 * given social account resolves to exactly one imin user.
 */
@Entity
@Table(name = "user_identities")
@Getter
@Setter
public class UserIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Lowercase provider key, e.g. {@code "google"} or {@code "apple"}. */
    @Column(nullable = false, length = 16)
    private String provider;

    /** The provider's stable subject identifier (the OIDC {@code sub} claim). */
    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    /** Email as reported by the provider at link time (may be a relay address). */
    @Column(length = 320)
    private String email;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    void onPersist() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
    }
}
