package com.imin.iminapi.audience.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in the erasure ledger (V99) — an address that asked to be forgotten.
 *
 * <p>Append-only. {@code orgId == null} means the whole imin account was erased
 * (platform-wide); a non-null {@code orgId} is one organizer's org-scoped DSAR.
 *
 * @see com.imin.iminapi.audience.service.AudienceBackfillJob the reader this exists for
 */
@Entity
@Table(name = "erased_addresses")
@Getter
@Setter
public class ErasedAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Null = platform-wide (buyer account erasure); set = that org's DSAR only. */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "email_normalized", nullable = false, length = 254)
    private String emailNormalized;

    @Column(name = "erased_at", nullable = false, updatable = false)
    private Instant erasedAt = Instant.now();

    @PrePersist
    void onPersist() {
        if (erasedAt == null) erasedAt = Instant.now();
    }
}
