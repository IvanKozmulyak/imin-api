package com.imin.iminapi.buyer.model;

import com.imin.iminapi.audience.service.EmailNormalizer;
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
 * One email address owned by a {@link BuyerAccount}. The orders join runs
 * through this table and reads only rows with {@code verified_at IS NOT NULL}
 * — verification is the entire security boundary of buyer accounts (§2.3).
 *
 * <p><b>Marker columns.</b> {@code verified_key} and {@code primary_marker}
 * exist because neither H2 (PG-compat, what the test suite runs) nor a
 * table-level UNIQUE takes a WHERE clause. They must stay in sync with
 * {@code verified_at} / primary-ness, which is why the only supported way to
 * change either is {@link #markVerified(Instant)}, {@link #makePrimary()} and
 * {@link #clearPrimary()} rather than raw setters. Invariants:
 * <pre>
 *   verifiedKey != null   &lt;=&gt;  verifiedAt != null
 *   primaryMarker != null  =&gt;  verifiedAt != null
 * </pre>
 */
@Entity
@Table(name = "buyer_account_emails")
@Getter
@Setter
public class BuyerAccountEmail {

    /** Address the account was created with. */
    public static final String ADDED_VIA_SIGNUP = "signup";
    /** Address supplied by the Google ID token. */
    public static final String ADDED_VIA_GOOGLE = "google";
    /** Address added later from the profile screen. */
    public static final String ADDED_VIA_MANUAL = "manual";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "buyer_account_id", nullable = false)
    private UUID buyerAccountId;

    /** As typed, for display. */
    @NotNull
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    /** {@code lower(trim(email))} — the join key against {@code orders}. */
    @NotNull
    @Column(name = "email_normalized", nullable = false, length = 254)
    private String emailNormalized;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @NotNull
    @Column(name = "added_via", nullable = false, length = 16)
    private String addedVia = ADDED_VIA_MANUAL;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Times.nowMicros();

    /** = {@link #emailNormalized} while verified, NULL otherwise. Do not set directly. */
    @Column(name = "verified_key", length = 254)
    private String verifiedKey;

    /** = {@link #buyerAccountId} while primary, NULL otherwise. Do not set directly. */
    @Column(name = "primary_marker")
    private UUID primaryMarker;

    /** Factory that keeps {@code email} / {@code email_normalized} consistent by construction. */
    public static BuyerAccountEmail of(UUID accountId, String rawEmail, String addedVia) {
        BuyerAccountEmail row = new BuyerAccountEmail();
        row.setBuyerAccountId(accountId);
        row.setEmail(rawEmail);
        row.setEmailNormalized(EmailNormalizer.normalize(rawEmail));
        row.setAddedVia(addedVia);
        return row;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean isPrimary() {
        return primaryMarker != null;
    }

    /** Marks the address verified and keeps the uniqueness marker in sync. */
    public void markVerified(Instant at) {
        this.verifiedAt = at;
        this.verifiedKey = emailNormalized;
    }

    /**
     * Makes this the account's primary address. Refuses on an unverified row —
     * the primary address is where transactional mail goes and what sign-in is
     * gated on, so an unverified primary would be a hole, not an inconvenience.
     */
    public void makePrimary() {
        if (verifiedAt == null) {
            throw new IllegalStateException("primary address must be verified first");
        }
        this.primaryMarker = buyerAccountId;
    }

    public void clearPrimary() {
        this.primaryMarker = null;
    }

    @PrePersist
    @PreUpdate
    void normalizeAndTruncate() {
        if (email != null && emailNormalized == null) {
            emailNormalized = EmailNormalizer.normalize(email);
        }
        // Re-assert the marker invariants on every write so no code path can
        // leave a verified row without its uniqueness key (or vice versa).
        verifiedKey = verifiedAt == null ? null : emailNormalized;
        if (verifiedAt == null) primaryMarker = null;
        if (createdAt == null) createdAt = Times.nowMicros();
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        if (verifiedAt != null) verifiedAt = verifiedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
