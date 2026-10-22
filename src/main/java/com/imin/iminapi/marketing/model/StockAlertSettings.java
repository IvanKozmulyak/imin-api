package com.imin.iminapi.marketing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** An event's stock-alert switches (V206); one row per event, written only by the settings PUT. */
@Entity
@Table(name = "stock_alert_settings")
@Getter
@Setter
public class StockAlertSettings {

    public static final String MODE_ASK = "ask";
    public static final String MODE_AUTO = "auto";

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "last_tickets_on", nullable = false)
    private boolean lastTicketsOn;

    /** Null = auto threshold. */
    @Column(name = "last_tickets_threshold")
    private Integer lastTicketsThreshold;

    @Column(name = "price_up_on", nullable = false)
    private boolean priceUpOn;

    @Column(name = "price_up_lead_hours", nullable = false)
    private int priceUpLeadHours = 24;

    @Column(nullable = false, length = 8)
    private String mode = MODE_ASK;

    @Column(name = "auto_enabled_by")
    private UUID autoEnabledBy;

    @Column(name = "auto_enabled_at")
    private Instant autoEnabledAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public boolean isAuto() {
        return MODE_AUTO.equals(mode);
    }

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        if (updatedAt != null) updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
        if (autoEnabledAt != null) autoEnabledAt = autoEnabledAt.truncatedTo(ChronoUnit.MICROS);
    }
}
