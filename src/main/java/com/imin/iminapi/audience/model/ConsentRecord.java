package com.imin.iminapi.audience.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only consent audit trail. Never UPDATE or DELETE in application code, except
 * {@code ConsentRecordRepository.markConfirmed}, which only sets {@code confirmed_at}.
 * Current state is denormalized onto {@link Membership#consentStatus} and
 * {@link Membership#consentBasis} for efficient send-gate queries.
 */
@Entity
@Table(name = "consent_records")
@Getter
@Setter
public class ConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;

    /** email (only channel in Tier C) */
    @Column(nullable = false, length = 16)
    private String channel = "email";

    /** subscribed | unsubscribed */
    @Column(nullable = false, length = 16)
    private String status;

    /** explicit | soft_opt_in | null */
    @Column(name = "lawful_basis", length = 16)
    private String lawfulBasis;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "proof_text", columnDefinition = "TEXT")
    private String proofText;

    /** Version of the consent sentence shown; null when the capture carried none. */
    @Column(name = "text_version", length = 32)
    private String textVersion;

    /** Order the consent was given on (checkout); null for other sources. */
    @Column(name = "order_id")
    private UUID orderId;

    /** The event the consent was given at (door QR); null elsewhere (V142). */
    @Column(name = "event_id")
    private UUID eventId;

    /** True for a sign-up whose address must be confirmed before it grants anything. */
    @Column(name = "confirmation_required", nullable = false)
    private boolean confirmationRequired;

    /** When the address was confirmed; the only column a later confirmation may set. */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    /** Recorded but not yet a lawful basis for either send gate. */
    public boolean isAwaitingConfirmation() {
        return confirmationRequired && confirmedAt == null;
    }

    @PrePersist
    void onPersist() {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
