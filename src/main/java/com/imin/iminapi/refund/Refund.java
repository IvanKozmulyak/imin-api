package com.imin.iminapi.refund;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A refund of part or all of an Order. One Order can have many Refund rows
 * (Stripe supports multiple partial refunds per charge); each Refund maps 1:1
 * to a Stripe Refund object via {@link #stripeRefundId}.
 *
 * <p>The {@code idempotency_key} is supplied by the dashboard client and stored
 * here so a retried POST returns the same row instead of creating a duplicate
 * Stripe Refund.
 */
@Entity
@Table(name = "refunds")
@Getter
@Setter
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "stripe_refund_id", length = 255)
    private String stripeRefundId;

    @Column(name = "stripe_charge_id", length = 255)
    private String stripeChargeId;

    @Column(name = "stripe_payment_intent_id", nullable = false, length = 255)
    private String stripePaymentIntentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "application_fee_refund_minor", nullable = false)
    private long applicationFeeRefundMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RefundStatus status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "initiated_by_user_id", nullable = false)
    private UUID initiatedByUserId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

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
