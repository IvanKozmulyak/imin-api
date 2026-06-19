package com.imin.iminapi.audience.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only consent audit trail. Never UPDATE or DELETE in application code.
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

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    @PrePersist
    void onPersist() {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
