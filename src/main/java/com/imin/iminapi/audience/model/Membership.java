package com.imin.iminapi.audience.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-org projection of a consumer's activity and consent state.
 * All behavioural aggregates are DERIVED FROM SOURCE rows (S1) — never incremented in place.
 * consent_status and consent_basis are denormalized here for efficient send-gate queries (M3).
 */
@Entity
@Table(name = "memberships",
       uniqueConstraints = @UniqueConstraint(name = "ux_memberships_org_consumer",
               columnNames = {"org_id", "consumer_id"}))
@Getter
@Setter
public class Membership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "membership_id")
    private UUID membershipId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    /** active | erase_pending */
    @Column(nullable = false, length = 16)
    private String status = "active";

    @Column(name = "display_name")
    private String displayName;

    @Column(length = 128)
    private String city;

    @Convert(converter = StringListJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> genres = new ArrayList<>();

    // ---- derived behavioural aggregates (recomputed from source rows) ----

    @Column(nullable = false)
    private int events = 0;

    @Column(nullable = false)
    private int attended = 0;

    @Column(name = "no_show", nullable = false)
    private int noShow = 0;

    @Column(nullable = false)
    private int orders = 0;

    @Column(name = "spend_minor", nullable = false)
    private long spendMinor = 0;

    @Column(name = "aov_minor", nullable = false)
    private long aovMinor = 0;

    @Column(name = "first_seen")
    private Instant firstSeen;

    @Column(name = "last_purchase")
    private Instant lastPurchase;

    @Column(name = "last_attended")
    private Instant lastAttended;

    @Column(name = "recency_days")
    private Integer recencyDays;

    // ---- RFM bands 0–5 ----

    @Column(name = "rfm_r", nullable = false)
    private short rfmR = 0;

    @Column(name = "rfm_f", nullable = false)
    private short rfmF = 0;

    @Column(name = "rfm_m", nullable = false)
    private short rfmM = 0;

    /** prospect | firsttime | repeat | vip | lapsing | dormant | wonback */
    @Column(nullable = false, length = 16)
    private String lifecycle = "prospect";

    @Column(name = "first_touch_src", nullable = false, length = 128)
    private String firstTouchSrc = "organic";

    @Column(name = "last_email_open")
    private Instant lastEmailOpen;

    @Column(name = "last_email_click")
    private Instant lastEmailClick;

    // ---- denormalized consent state (M3) ----

    /** never | subscribed | unsubscribed */
    @Column(name = "consent_status", nullable = false, length = 16)
    private String consentStatus = "never";

    /** explicit | soft_opt_in | NULL */
    @Column(name = "consent_basis", length = 16)
    private String consentBasis;

    // ---- denormalized SMS consent state (Phase 3 / §2.2 / §7) ----

    /** Buyer phone in E.164, projected from orders.buyer_phone. NULL until an opt-in arrives. */
    @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    /** never | subscribed | unsubscribed — the SMS analogue of consentStatus. */
    @Column(name = "sms_consent_status", nullable = false, length = 16)
    private String smsConsentStatus = "never";

    /** explicit | NULL — SMS accepts explicit ONLY (no soft opt-in, §7). */
    @Column(name = "sms_consent_basis", length = 16)
    private String smsConsentBasis;

    // ---- profile ----

    @Column(name = "nps")
    private Short nps;

    @Column(length = 64)
    private String vibe;

    @Column(columnDefinition = "TEXT")
    private String quote;

    @Convert(converter = StringListJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> tags = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Art.17 erasure schedule: set to now()+30d when erase requested. */
    @Column(name = "erase_at")
    private Instant eraseAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
