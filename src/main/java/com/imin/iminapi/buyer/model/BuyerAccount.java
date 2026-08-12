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
 * A buyer (fan) account. Deliberately not a {@code User}: {@code users.email_lower}
 * is globally unique with a NOT NULL {@code org_id}, so an organizer buying a
 * ticket with their work address would collide with their own buyer account
 * (epic §2.1).
 *
 * <p>There is no email column here — addresses live in {@link BuyerAccountEmail}
 * because one account owns several verified addresses (§2.3) — and no
 * {@code verified_at}: "this account may sign in" is derived from the primary
 * address row being verified. {@link #activatedAt} is an event timestamp for
 * audit and for the sweeper's orphan rule, never a permission.
 *
 * <p>{@code @Column(nullable = false)} enforces nothing at runtime in this
 * project — spring-boot-starter-validation on the classpath disables
 * Hibernate's own not-null check — so the entity-level guard is {@link NotNull}.
 */
@Entity
@Table(name = "buyer_accounts")
@Getter
@Setter
public class BuyerAccount {

    /** Normal account. */
    public static final String STATUS_ACTIVE = "active";
    /** Deletion scheduled; {@link #deleteAt} carries the due date (R1.5). */
    public static final String STATUS_DELETE_PENDING = "delete_pending";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** NULL for Google-only accounts, exactly as {@code users.password_hash} is. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "city", length = 128)
    private String city;

    /** Transactional-email language; one of en/es/fr/uk. */
    @Column(name = "locale", length = 5)
    private String locale;

    @NotNull
    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    /** When the account first had any verified address. Audit only — never a permission. */
    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "delete_at")
    private Instant deleteAt;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Times.nowMicros();

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Times.nowMicros();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (createdAt == null) createdAt = Times.nowMicros();
        updatedAt = Times.nowMicros();
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        if (activatedAt != null) activatedAt = activatedAt.truncatedTo(ChronoUnit.MICROS);
        if (deleteAt != null) deleteAt = deleteAt.truncatedTo(ChronoUnit.MICROS);
        if (lastLoginAt != null) lastLoginAt = lastLoginAt.truncatedTo(ChronoUnit.MICROS);
    }
}
