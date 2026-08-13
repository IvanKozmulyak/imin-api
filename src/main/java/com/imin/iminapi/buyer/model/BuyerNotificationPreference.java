package com.imin.iminapi.buyer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * The per-account notification switches (V85, spec §4.4).
 *
 * <p>Two columns, and two deliberate absences carried over from V85's own
 * comment. {@code ticket_delivery} is absent because delivering a ticket
 * someone paid for is performance of a contract, not marketing, and carries no
 * opt-out. {@code organizer_updates} is absent because it is DERIVED from
 * membership consent (§6.3) — storing it would create a second source of truth
 * for a legal record.
 *
 * <p>A missing row means defaults, not "no preferences": the row is created
 * lazily on first write. Readers must treat absent as {@code eventReminders =
 * true, productNews = false}.
 */
@Entity
@Table(name = "buyer_notification_preferences")
@Getter @Setter @NoArgsConstructor
public class BuyerNotificationPreference {

    @Id
    @Column(name = "buyer_account_id")
    private UUID buyerAccountId;

    @Column(name = "event_reminders", nullable = false)
    private boolean eventReminders = true;

    /**
     * Storage for a list that does not exist. Epic §6.4 cut "News from IMIN":
     * no sender, no lawful basis. The column ships so the row can appear the
     * day a sender does; nothing reads it and no UI renders it.
     */
    @Column(name = "product_news", nullable = false)
    private boolean productNews = false;

    public BuyerNotificationPreference(UUID buyerAccountId) {
        this.buyerAccountId = buyerAccountId;
    }
}
