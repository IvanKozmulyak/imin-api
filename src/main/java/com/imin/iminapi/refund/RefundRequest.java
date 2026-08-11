package com.imin.iminapi.refund;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "refund_requests")
@Getter
@Setter
public class RefundRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Short, human-dictatable reference (V81) — {@code REQ-8K2M-26}. This is the id
     * a buyer quotes to support; the UUID above is the machine id and is not readable
     * out loud. UNIQUE at the DB level, assigned once at creation, never rewritten.
     */
    @Column(name = "reference", nullable = false, length = 16, updatable = false)
    private String reference;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "buyer_email", nullable = false, length = 254)
    private String buyerEmail;

    @Column(name = "buyer_phone", length = 32)
    private String buyerPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundRequestReason reason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RefundRequestStatus status;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "refund_id")
    private UUID refundId;

    /**
     * Set to {@code order_id} while {@code status==PENDING}, NULL on any
     * terminal transition. {@code UNIQUE(pending_marker)} enforces
     * "one open request per order" — see V30 migration.
     */
    @Column(name = "pending_marker")
    private UUID pendingMarker;

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
