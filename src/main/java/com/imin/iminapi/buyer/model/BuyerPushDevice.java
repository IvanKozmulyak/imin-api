package com.imin.iminapi.buyer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One installed app instance that can receive push notifications (V92).
 *
 * <p>{@code expoToken} is globally unique, not unique per account: a physical
 * device is one delivery address, so a second buyer signing in on it re-points
 * this row rather than adding another. Without that, the previous owner keeps
 * receiving alerts on a phone that is no longer theirs.
 *
 * <p>The token is stored raw. Unlike {@code buyer_sessions.token_hash} it
 * authenticates nobody — it is an address Expo delivers to, and a hash of it
 * could not be sent to.
 */
@Entity
@Table(name = "buyer_push_devices")
@Getter @Setter @NoArgsConstructor
public class BuyerPushDevice {

    @Id
    // Strategy is explicit, matching the sibling buyer entities. A bare
    // @GeneratedValue lets the provider pick, and on Hibernate 6 that is a
    // sequence — wrong for a UUID column.
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "buyer_account_id", nullable = false)
    private UUID buyerAccountId;

    @Column(name = "expo_token", nullable = false, length = 255)
    private String expoToken;

    /** {@code ios} or {@code android}. Display and diagnostics only. */
    @Column(name = "platform", nullable = false, length = 16)
    private String platform;

    @Column(name = "locale", length = 8)
    private String locale;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Set on sign-out, or when Expo reports the token as DeviceNotRegistered. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Microsecond truncation, house-wide: Postgres stores microseconds and an
     * untruncated in-memory {@code Instant} would not equal what comes back.
     */
    @PrePersist
    void stampMicros() {
        if (createdAt == null) createdAt = Instant.now();
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        if (lastSeenAt == null) lastSeenAt = createdAt;
        lastSeenAt = lastSeenAt.truncatedTo(ChronoUnit.MICROS);
    }
}
