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

    @Column(name = "promo_code_id")
    private UUID promoCodeId;

    /** "free" today; "stripe" once the paid webhook is wired. */
    @Column(name = "payment_method", nullable = false, length = 16)
    private String paymentMethod;

    @Column(name = "stripe_session_id", length = 128)
    private String stripeSessionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
    }
}
