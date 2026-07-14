package com.imin.iminapi.marketing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meta_capi_events")
@Getter
@Setter
public class MetaCapiEvent {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_DEAD = "dead";

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    // orders.token — the shared browser<->CAPI dedup key (Meta event_id). See V60.
    @Column(name = "order_token", nullable = false)
    private String orderToken;

    @Column(name = "pixel_id", nullable = false)
    private String pixelId;

    @Column(name = "event_name", nullable = false)
    private String eventName = "Purchase";

    @Column(name = "email_sha256", nullable = false)
    private String emailSha256;

    @Column(name = "value_minor", nullable = false)
    private long valueMinor;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "fbp")
    private String fbp;

    @Column(name = "fbc")
    private String fbc;

    @Column(name = "event_time", nullable = false)
    private long eventTime;

    @Column(name = "status", nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    private short attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
