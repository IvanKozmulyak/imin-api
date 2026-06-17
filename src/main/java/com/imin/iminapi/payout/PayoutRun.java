package com.imin.iminapi.payout;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Trigger ledger for an imin-INITIATED bank payout (Track B, Option B).
 *
 * <p>Unlike the Track A {@code Settlement} read-model — a POST-HOC mirror of
 * Stripe payout/transfer state delivered by webhooks — a {@code PayoutRun} row is
 * written BEFORE the Stripe {@code Payout.create} call. Its
 * {@link #idempotencyKey} ({@code "evt:<eventId>:attempt:<attempt>"}) is
 * DETERMINISTIC and reused on the Stripe call via
 * {@code RequestOptions.setIdempotencyKey(...)}, so a crash between the
 * {@code SUBMITTED} write and the Stripe ack — or a replica overlap — replays to
 * the SAME {@code po_} instead of double-paying. The {@code UNIQUE} constraint on
 * {@link #idempotencyKey} is the pre-call guard that makes concurrent replicas
 * converge on a single row.
 *
 * <p>Status (stored lowercase via {@link PayoutRunStatusConverter}):
 * {@code PLANNED -> SUBMITTED -> PAID | FAILED}. {@link #attempt} is bumped only
 * after a {@code FAILED} run, minting a fresh idempotency key.
 */
@Entity
@Table(name = "payout_runs")
@Getter
@Setter
public class PayoutRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /**
     * The connected account (acct_...) the payout is created ON. The org-level
     * one-in-flight double-pay guard keys off this column.
     */
    @Column(name = "stripe_account_id", nullable = false, length = 64)
    private String stripeAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 8)
    private String currency;

    @Convert(converter = PayoutRunStatusConverter.class)
    @Column(nullable = false, length = 16)
    private PayoutRunStatus status;

    /** Bumped only when a prior run for the event is FAILED (carried in the key). */
    @Column(nullable = false)
    private int attempt = 1;

    /**
     * Deterministic {@code "evt:<eventId>:attempt:<attempt>"}. Reused on
     * {@code Payout.create} so a re-run never double-pays. UNIQUE in the DB.
     */
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /** po_..., set when the run reaches SUBMITTED; null while PLANNED. UNIQUE in the DB. */
    @Column(name = "stripe_payout_id", length = 64)
    private String stripePayoutId;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

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
