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
     * imin's own marketing mail — opted into on the finish-registration step
     * (V91), unticked by default.
     *
     * <p>V85 shipped this column for a sender that did not exist and epic §6.4
     * cut the list for want of a lawful basis. The basis now exists: an
     * explicit, separately-recorded opt-in with the on-screen sentence stored
     * in {@link #productNewsProof}. <b>There is still no sender.</b> Nothing in
     * the platform reads this to mail anybody yet, and whatever does must check
     * this flag and honour an unsubscribe.
     */
    @Column(name = "product_news", nullable = false)
    private boolean productNews = false;

    /**
     * When the opt-in was given, and the sentence shown beside it (V91).
     *
     * <p>Kept here rather than in {@code consent_records}: that table is keyed
     * by {@code membership_id} and scoped to one organizer, and this consent has
     * neither — it is between the buyer and imin.
     */
    @Column(name = "product_news_at")
    private java.time.Instant productNewsAt;

    @Column(name = "product_news_proof", columnDefinition = "TEXT")
    private String productNewsProof;

    /**
     * The in-app switch for drop-alert pushes (V92). The OS permission is the
     * primary gate; this is what lets someone keep the permission and still
     * silence imin. Default true — registering a device already required an
     * affirmative OS prompt.
     *
     * <p>Absent row means defaults, same as the two columns above: readers must
     * treat a missing row as {@code pushDropAlerts = true}.
     */
    @Column(name = "push_drop_alerts", nullable = false)
    private boolean pushDropAlerts = true;

    public BuyerNotificationPreference(UUID buyerAccountId) {
        this.buyerAccountId = buyerAccountId;
    }
}
