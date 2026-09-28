package com.imin.iminapi.audience.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One confirmation email sent for a door QR / survey sign-up; the emailed token is a signature over {@link #id}. */
@Entity
@Table(name = "consent_confirmation_tokens")
@Getter
@Setter
public class ConsentConfirmationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "membership_id", nullable = false, updatable = false)
    private UUID membershipId;

    /** The sign-up that triggered this email. */
    @Column(name = "consent_record_id", nullable = false, updatable = false)
    private UUID consentRecordId;

    @Column(nullable = false, length = 8, updatable = false)
    private String locale;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Set once, by the first confirm attempt that reaches this row; later attempts are refused. */
    @Column(name = "used_at")
    private Instant usedAt;
}
