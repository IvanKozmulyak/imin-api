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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TicketTierKind kind;

    @Column(name = "price_minor", nullable = false)
    private int priceMinor;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int sold = 0;

    @Column(name = "sale_closes_at")
    private Instant saleClosesAt;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
