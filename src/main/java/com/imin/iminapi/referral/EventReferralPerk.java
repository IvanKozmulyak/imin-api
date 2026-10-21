package com.imin.iminapi.referral;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** An event's crew-link perk (V199). Read-mapped; every write is a native statement in the repository. */
@Entity
@Immutable
@Table(name = "event_referral_perks")
@Getter
public class EventReferralPerk {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(nullable = false)
    private short threshold;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
