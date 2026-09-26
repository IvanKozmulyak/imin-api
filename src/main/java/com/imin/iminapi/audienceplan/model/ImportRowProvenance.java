package com.imin.iminapi.audienceplan.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Where one imported contact came from and whether its own proof was accepted.
 * An {@code organizer_import_row} consent counts only with an accepted row here.
 */
@Entity
@Table(name = "import_row_provenance")
@Getter
@Setter
public class ImportRowProvenance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "import_id", nullable = false)
    private UUID importId;

    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;

    /** The consent record this accepted row proves; null for rejected rows. */
    @Column(name = "consent_record_id")
    private UUID consentRecordId;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "source_platform", length = 64)
    private String sourcePlatform;

    @Column(name = "export_date")
    private LocalDate exportDate;

    @Column(columnDefinition = "TEXT")
    private String events;

    @Column(name = "last_purchase_date")
    private LocalDate lastPurchaseDate;

    /** opted_in | unsubscribed | none */
    @Column(name = "marketing_status", nullable = false, length = 16)
    private String marketingStatus;

    @Column(name = "proof_ref", length = 500)
    private String proofRef;

    @Column(nullable = false)
    private boolean accepted;

    @Column(name = "reject_reason", length = 32)
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
