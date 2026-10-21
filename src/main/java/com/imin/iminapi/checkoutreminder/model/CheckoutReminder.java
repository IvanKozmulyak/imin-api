package com.imin.iminapi.checkoutreminder.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * One unfinished-checkout reminder (V200). Read-only as an entity: capture, the sender, the
 * webhooks, unsubscribe, erasure and the purge all write through column-scoped repository
 * UPDATEs, so no writer can put back a column another one committed.
 */
@Entity
@Immutable
@Table(name = "checkout_reminders")
@Getter
public class CheckoutReminder {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "tier_id", nullable = false)
    private UUID tierId;

    @Column(nullable = false)
    private int qty;

    @Column(name = "stripe_session_id", nullable = false)
    private String stripeSessionId;

    /** Null once wiped by retention or erasure. */
    @Column(name = "email_normalized")
    private String emailNormalized;

    @Column(name = "buyer_locale")
    private String buyerLocale;

    @Column(name = "promo_used", nullable = false)
    private boolean promoUsed;

    /** The verbatim sentence the buyer ticked; null once wiped. */
    @Column(name = "consent_proof")
    private String consentProof;

    @Column(name = "consent_text_version")
    private String consentTextVersion;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CheckoutReminderStatus status;

    @Enumerated(EnumType.STRING)
    private CheckoutReminderReason reason;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "unsubscribed_at")
    private Instant unsubscribedAt;

    @Column(name = "complained_at")
    private Instant complainedAt;

    @Column(name = "recovered_order_id")
    private UUID recoveredOrderId;

    @Column(name = "recovered_at")
    private Instant recoveredAt;

    @Column(name = "address_wiped_at")
    private Instant addressWipedAt;
}
