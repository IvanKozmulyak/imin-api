package com.imin.iminapi.dispute;

import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One chargeback, keyed on its Stripe {@code du_} id. This is the authoritative dispute
 * state — the {@code settlements} row a dispute also annotates is a read-model for the
 * Payouts UI and no longer gates anything.
 *
 * <p>{@link #eventId} / {@link #orderId} are nullable on purpose: a dispute whose charge
 * resolves to no imin order still has to be recorded, because an OPEN dispute freezes the
 * org's payouts whether or not we can attribute it.
 */
@Entity
@Table(name = "disputes")
@Getter
@Setter
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "stripe_dispute_id", nullable = false, unique = true, length = 64)
    private String stripeDisputeId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "stripe_charge_id", length = 64)
    private String stripeChargeId;

    @Column(name = "stripe_payment_intent_id", length = 255)
    private String stripePaymentIntentId;

    /** The disputed FACE VALUE in minor units. Stripe's dispute fee is not in here. */
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 8)
    private String currency;

    @Convert(converter = DisputeStatusConverter.class)
    @Column(nullable = false, length = 32)
    private DisputeStatus status = DisputeStatus.OPEN;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Stripe {@code event.created} of the delivery that last wrote this row. Stripe does not
     * guarantee delivery order, so an event older than this stamp is dropped rather than
     * allowed to drag a closed dispute back to OPEN.
     */
    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void touch() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = Times.nowMicros();
        if (openedAt != null) openedAt = openedAt.truncatedTo(ChronoUnit.MICROS);
        if (closedAt != null) closedAt = closedAt.truncatedTo(ChronoUnit.MICROS);
        if (lastEventAt != null) lastEventAt = lastEventAt.truncatedTo(ChronoUnit.MICROS);
    }
}
