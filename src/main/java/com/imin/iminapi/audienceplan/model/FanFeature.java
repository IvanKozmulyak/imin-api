package com.imin.iminapi.audienceplan.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Plan-tool features for one membership, computed from paid purchases only.
 * One row per membership; erased with it (FK cascade plus an explicit DSAR delete).
 */
@Entity
@Table(name = "fan_features")
@Getter
@Setter
public class FanFeature {

    @Id
    @Column(name = "membership_id")
    private UUID membershipId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "paid_orders", nullable = false)
    private int paidOrders = 0;

    @Column(name = "first_paid_purchase_at")
    private Instant firstPaidPurchaseAt;

    @Column(name = "last_paid_purchase_at")
    private Instant lastPaidPurchaseAt;

    /** loyal | repeat | first_timer | lapsing | dormant | imported | none */
    @Column(name = "class", nullable = false, length = 16)
    private String fanClass = "none";

    /** JSON object: genre bucket key to weight. */
    @Column(columnDefinition = "TEXT")
    private String taste;

    /** JSON array. */
    @Column(columnDefinition = "TEXT")
    private String cities;

    /** JSON array. */
    @Column(columnDefinition = "TEXT")
    private String formats;

    @Column(name = "no_show_n", nullable = false)
    private int noShowN = 0;

    @Column(name = "avg_group_size", precision = 6, scale = 3)
    private BigDecimal avgGroupSize;

    @Column(name = "sends_30d", nullable = false)
    private int sends30d = 0;

    @Column(name = "last_contact_from_person_at")
    private Instant lastContactFromPersonAt;

    @Column(name = "logic_version", nullable = false)
    private int logicVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
