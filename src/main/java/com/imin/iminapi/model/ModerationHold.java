package com.imin.iminapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A staff measure against an organizer (V189): ticket sales suspended and/or payouts held, for the
 * whole org ({@code eventId} null) or one event. Several may be active at once, one per claim.
 *
 * <p>Every column is {@code updatable = false}: the row is inserted once, and the only later writes
 * are the guarded UPDATEs in {@code ModerationHoldRepository} (lift, notice stamps), so a stale
 * full-entity save can never reopen a lifted hold or clear a stamp.
 */
@Entity
@Table(name = "moderation_holds")
@Getter
@Setter
public class ModerationHold {

    public static final java.util.Set<String> GROUNDS = java.util.Set.of(
            "aup_breach", "terms_breach", "fraud", "safety", "authority_order", "reported_event");

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "event_id", updatable = false)
    private UUID eventId;

    @Column(name = "suspend_sales", nullable = false, updatable = false)
    private boolean suspendSales;

    @Column(name = "hold_payouts", nullable = false, updatable = false)
    private boolean holdPayouts;

    @Column(nullable = false, updatable = false, length = 32)
    private String ground;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String facts;

    @Column(name = "reason_withheld", nullable = false, updatable = false)
    private boolean reasonWithheld;

    @Column(name = "placed_by", nullable = false, updatable = false)
    private UUID placedBy;

    @Column(name = "placed_by_email", updatable = false, length = 320)
    private String placedByEmail;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "notice_sent_at", updatable = false)
    private Instant noticeSentAt;

    @Column(name = "lifted_at", updatable = false)
    private Instant liftedAt;

    @Column(name = "lifted_by", updatable = false)
    private UUID liftedBy;

    @Column(name = "lifted_by_email", updatable = false, length = 320)
    private String liftedByEmail;

    @Column(name = "lift_note", updatable = false, length = 1000)
    private String liftNote;

    @Column(name = "lift_notice_sent_at", updatable = false)
    private Instant liftNoticeSentAt;

    public boolean isActive() {
        return liftedAt == null;
    }
}
