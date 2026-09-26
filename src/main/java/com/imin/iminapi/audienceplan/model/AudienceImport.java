package com.imin.iminapi.audienceplan.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One real (non-preview) CSV contact import and its outcome counts. */
@Entity
@Table(name = "audience_imports")
@Getter
@Setter
public class AudienceImport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "created_by")
    private UUID createdBy;

    /** SHA-256 of the organizer's original file, as computed by their browser. */
    @Column(name = "original_file_sha256", length = 64)
    private String originalFileSha256;

    /** SHA-256 of the bytes the API received (the dashboard's rewritten file). */
    @Column(name = "uploaded_file_sha256", length = 64)
    private String uploadedFileSha256;

    @Column(name = "source_platform", length = 64)
    private String sourcePlatform;

    @Column(name = "export_date")
    private LocalDate exportDate;

    @Column(name = "proof_ref", length = 500)
    private String proofRef;

    @Column(name = "attestation_version", length = 32)
    private String attestationVersion;

    @Column(name = "rows_total", nullable = false)
    private int rowsTotal;

    @Column(name = "rows_explicit", nullable = false)
    private int rowsExplicit;

    @Column(name = "rows_no_basis", nullable = false)
    private int rowsNoBasis;

    @Column(name = "rows_rejected", nullable = false)
    private int rowsRejected;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
