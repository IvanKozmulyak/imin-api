package com.imin.iminapi.checkoutreminder.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** The organizer's on/off switch for the reminder (V200); written by an upsert in its repository. */
@Entity
@Immutable
@Table(name = "org_checkout_reminder_settings")
@Getter
public class OrgCheckoutReminderSetting {

    @Id
    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Column(name = "enabled_by")
    private UUID enabledBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
