package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "promo_codes")
@Getter
@Setter
public class PromoCode {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(name = "discount_pct", nullable = false)
    private int discountPct;

    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(nullable = false)
    private boolean enabled = true;
}
