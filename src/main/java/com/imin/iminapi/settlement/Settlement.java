package com.imin.iminapi.settlement;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A read-model row mirroring a single Stripe {@code transfer} (tr_...) or
 * {@code payout} (po_...) object, populated from Stripe webhooks. This table
 * moves NO money and is not a ledger — it exists so the payouts endpoints can be
 * served from our DB rather than a live Stripe list call.
 *
 * <p>One row per Stripe object: {@link #stripeObjectId} is UNIQUE so a webhook
 * replay (or a second distinct event id for the same object) upserts the same
 * row. Org attribution is the connected account the object lives on
 * (organizations.stripe_account_id). Event attribution, when known, is a CSV of
 * event UUIDs in {@link #eventIds} — payouts batch many transfers and often
 * cover none/several, hence the nullable CSV rather than a hard FK.
 *
 * <p>Enums are persisted in their lowercase Stripe wire form via
 * {@link SettlementStatusConverter} / {@link SettlementObjectTypeConverter} so
 * the stored VARCHAR matches the V43 migration literals and the FE status union.
 */
@Entity
@Table(name = "settlements")
@Getter
@Setter
public class Settlement {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "stripe_object_id", nullable = false, length = 64)
    private String stripeObjectId;

    @Convert(converter = SettlementObjectTypeConverter.class)
    @Column(name = "object_type", nullable = false, length = 16)
    private SettlementObjectType objectType;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 8)
    private String currency;

    @Convert(converter = SettlementStatusConverter.class)
    @Column(nullable = false, length = 24)
    private SettlementStatus status;

    /** CSV of event UUIDs this row covers; null when unknown/unattributed. */
    @Column(name = "event_ids", columnDefinition = "text")
    private String eventIds;

    /** Stripe payout arrival_date when known; null for transfers / pending payouts. */
    @Column(name = "arrival_at")
    private Instant arrivalAt;

    /**
     * When a PAYOUT row reached PAID (payout arrival_date if present, else the
     * ingest time). Null for transfers and for payouts not yet paid. Drives the
     * "this month" summary window.
     */
    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void touch() {
        Instant now = Times.nowMicros();
        if (createdAt == null) createdAt = now;
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
