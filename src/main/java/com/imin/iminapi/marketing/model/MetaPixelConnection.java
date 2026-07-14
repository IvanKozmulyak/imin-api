package com.imin.iminapi.marketing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meta_pixel_connections")
@Getter
@Setter
public class MetaPixelConnection {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id")
    private UUID eventId; // null = org-wide default

    @Column(name = "pixel_id", nullable = false)
    private String pixelId;

    @Column(name = "capi_access_token_enc", nullable = false)
    private String capiAccessTokenEnc;

    @Column(name = "test_event_code")
    private String testEventCode;

    @Column(name = "status", nullable = false)
    private String status = "active";

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
