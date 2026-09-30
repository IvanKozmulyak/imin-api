package com.imin.iminapi.predictor.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** An org's link to an outside ticketing or social account; the token is stored encrypted (V162). */
@Entity
@Table(name = "org_connector")
@Getter
@Setter
public class OrgConnector {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** shotgun | dice | instagram. */
    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "token_enc", nullable = false, columnDefinition = "TEXT")
    private String tokenEnc;

    @Column(length = 512)
    private String scopes;

    @Column(name = "connected_by", nullable = false)
    private UUID connectedBy;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        connectedAt = connectedAt == null ? Times.nowMicros() : connectedAt.truncatedTo(ChronoUnit.MICROS);
        if (revokedAt != null) revokedAt = revokedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
