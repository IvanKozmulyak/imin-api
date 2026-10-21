package com.imin.iminapi.referral;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * A referrer order's unlocked perk (V199). {@code label}/{@code threshold} are the snapshot the buyer was promised,
 * so a later edit of the perk never changes it. Read-mapped; writes go through the repository's conditional updates.
 */
@Entity
@Immutable
@Table(name = "referral_perk_unlocks")
@Getter
public class ReferralPerkUnlock {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(nullable = false)
    private short threshold;

    @Column(name = "unlocked_at", nullable = false)
    private Instant unlockedAt;

    @Column(name = "given_at")
    private Instant givenAt;

    @Column(name = "given_via", length = 16)
    private String givenVia;

    @Column(name = "given_by_label", length = 120)
    private String givenByLabel;
}
