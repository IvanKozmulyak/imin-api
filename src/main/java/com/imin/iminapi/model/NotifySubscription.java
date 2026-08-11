package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A buyer's request to be notified when tickets are released for a given event.
 *
 * Captured by {@code POST /api/v1/public/events/{id}/notify}. The (event_id, email)
 * pair is unique per subscription; email is lowercased server-side before insert so
 * case-variants of the same address don't double-subscribe.
 *
 * <p>{@code notifiedAt} is the one-shot delivery marker consumed by
 * {@code NotifyReleaseSender}: {@code null} means the buyer is still owed the
 * "tickets are available" email, non-null means it has been sent (or deliberately
 * suppressed) and must not be sent again. Re-subscribing after a notification resets
 * it to {@code null}, re-arming the row for the next release.
 */
@Entity
@Table(name = "notify_subscriptions")
@Getter
@Setter
public class NotifySubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    /** When the release email went out (or was suppressed). Null ⇒ still pending. */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    /**
     * Consent provenance (V77), captured at subscribe time and refreshed on re-arm.
     * All nullable — rows written before V77 have none, and a request can arrive with
     * no User-Agent. {@code consentText} is the verbatim promise the buyer was shown,
     * stored per row so a later copy change can't rewrite what they agreed to.
     */
    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "consent_text", length = 255)
    private String consentText;

    /** Buyer's UI language (en/es/fr/uk) — picks the notify-release email variant. Null ⇒ English. */
    @Column(length = 5)
    private String locale;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        if (notifiedAt != null) notifiedAt = notifiedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
