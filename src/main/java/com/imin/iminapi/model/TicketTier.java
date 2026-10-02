package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_tiers")
@Getter
@Setter
public class TicketTier {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "price_minor", nullable = false)
    private int priceMinor;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int sold = 0;

    @Column(nullable = false)
    private int reserved = 0;

    @Column(name = "sale_starts_at")
    private Instant saleStartsAt;

    @Column(name = "sale_closes_at")
    private Instant saleClosesAt;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /**
     * Stripe Product id (prod_...) on the platform account. Updated only by
     * {@code TicketTierRepository.updateStripeIdsIfPriceUnchanged}; no full-entity update can write it.
     */
    @Column(name = "stripe_product_id", length = 64, updatable = false)
    private String stripeProductId;

    /**
     * Stripe Price id (price_...) on the platform account. Updated only by
     * {@code TicketTierRepository.updateStripeIdsIfPriceUnchanged}; no full-entity update can write it.
     */
    @Column(name = "stripe_price_id", length = 64, updatable = false)
    private String stripePriceId;

    /** Sweep backoff; written only by the sweep claim and the id write in TicketTierRepository. */
    @Column(name = "stripe_sync_attempts", insertable = false, updatable = false)
    private int stripeSyncAttempts;

    /** Sweep backoff; written only by the sweep claim and the id write in TicketTierRepository. */
    @Column(name = "stripe_sync_next_at", insertable = false, updatable = false)
    private Instant stripeSyncNextAt;
}
