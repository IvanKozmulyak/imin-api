package com.imin.iminapi.marketing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A stock alert for one tier (V206): inserted by {@code insertIfAbsent}, every later change through the repository's
 * conditional updates ({@code updatable = false}), so no writer can overwrite another.
 */
@Entity
@Table(name = "stock_alerts")
@Getter
@Setter
public class StockAlert {

    public static final String READY = "ready";
    public static final String DRAFTED = "drafted";
    public static final String DISMISSED = "dismissed";
    public static final String EXPIRED = "expired";
    public static final String WITHDRAWN = "withdrawn";

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "tier_id", nullable = false, updatable = false)
    private UUID tierId;

    /** {@link StockAlertKind#wire()}. */
    @Column(nullable = false, length = 16, updatable = false)
    private String kind;

    @Column(nullable = false, length = 16, updatable = false)
    private String status;

    /** The facts read when the alert was prepared, refreshed while ready and at review. */
    @Column(name = "facts_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String factsJson;

    /** The facts every recipient's email quotes, frozen once at the first drive. */
    @Column(name = "facts_at_send_json", columnDefinition = "TEXT", updatable = false)
    private String factsAtSendJson;

    @Column(name = "reach_at_prepare", nullable = false, updatable = false)
    private int reachAtPrepare;

    @Column(nullable = false, length = 2, updatable = false)
    private String locale;

    @Column(name = "campaign_id", updatable = false)
    private UUID campaignId;

    @Column(name = "withdrawn_reason", length = 24, updatable = false)
    private String withdrawnReason;

    @Column(name = "expired_reason", length = 24, updatable = false)
    private String expiredReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "acted_at", updatable = false)
    private Instant actedAt;

    @Column(name = "acted_by", updatable = false)
    private UUID actedBy;
}
