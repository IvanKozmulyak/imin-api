package com.imin.iminapi.audience.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Suppression entry for two scopes:
 * <ul>
 *   <li>{@code marketing}      — org-scoped; one per (org_id, membership_id)</li>
 *   <li>{@code deliverability} — platform-shared; one per normalized_email</li>
 * </ul>
 * Uniqueness for each scope is enforced in the application layer (M4 — partial
 * unique indexes are not H2-compatible).
 */
@Entity
@Table(name = "suppression_entries")
@Getter
@Setter
public class SuppressionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** marketing | deliverability */
    @Column(nullable = false, length = 20)
    private String scope;

    /** Set for marketing scope; null for deliverability */
    @Column(name = "org_id")
    private UUID orgId;

    /** Set for marketing scope; null for deliverability */
    @Column(name = "membership_id")
    private UUID membershipId;

    /** Set for deliverability scope; null for marketing */
    @Column(name = "normalized_email", length = 254)
    private String normalizedEmail;

    /** hard-bounce | unsubscribe | spam | manual */
    @Column(nullable = false, length = 24)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant since = Instant.now();

    @Column(name = "system_owned", nullable = false)
    private boolean systemOwned = false;

    @PrePersist
    void onPersist() {
        if (since == null) since = Instant.now();
    }

    public static final String SCOPE_MARKETING = "marketing";
    public static final String SCOPE_DELIVERABILITY = "deliverability";

    public static final String REASON_HARD_BOUNCE = "hard-bounce";
    public static final String REASON_UNSUBSCRIBE = "unsubscribe";
    public static final String REASON_SPAM = "spam";
    public static final String REASON_MANUAL = "manual";
}
