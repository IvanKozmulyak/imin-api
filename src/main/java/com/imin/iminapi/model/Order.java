package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A buyer's confirmed order. Created today only by the free-checkout path
 * (totalMinor == 0); a follow-up will wire the Stripe webhook to also write
 * here on {@code checkout.session.completed} so paid orders share the same
 * persistence path.
 *
 * <p>{@code token} is a URL-safe random string used as the public lookup
 * key — never the UUID — so {@code /order/{token}} URLs cannot be enumerated.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(nullable = false, length = 8)
    private String currency;

    /**
     * Application fee (platform cut) Stripe deducted from this order, in minor units.
     * Snapshotted at issuance from PaymentIntent.applicationFeeAmount so refund
     * proration can compute the proportional fee refund without an extra Stripe call.
     * 0 for free orders.
     */
    @Column(name = "application_fee_minor", nullable = false)
    private long applicationFeeMinor;

    @Column(name = "promo_code_id")
    private UUID promoCodeId;

    /** "free" today; "stripe" once the paid webhook is wired. */
    @Column(name = "payment_method", nullable = false, length = 16)
    private String paymentMethod;

    @Column(name = "stripe_session_id", length = 128)
    private String stripeSessionId;

    @Column(name = "stripe_payment_intent_id", length = 128)
    private String stripePaymentIntentId;

    /** Buyer phone captured on the order-confirmation SMS opt-in (§4). E.164, nullable. */
    @Column(name = "buyer_phone", length = 20)
    private String buyerPhone;

    /**
     * True when the buyer ticked the unchecked-by-default SMS marketing opt-in
     * on the order-confirmation page (§4). Consent proof lives on the membership's
     * consent_records; this flag is the order-level snapshot the projector reads.
     */
    @Column(name = "sms_marketing_opt_in", nullable = false)
    private boolean smsMarketingOptIn = false;

    /**
     * Cookie-consent-derived ads-consent snapshot captured at order creation (§7). Backs
     * {@code orders.ads_consent} (V58, {@code DEFAULT false}); the Ads-CAPI lawful basis.
     * {@code MetaCapiOutboxWriter} only emits a server-side Meta event when this is true.
     */
    @Column(name = "ads_consent", nullable = false)
    private boolean adsConsent = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
    }
}
