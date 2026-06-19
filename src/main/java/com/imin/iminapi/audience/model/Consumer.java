package com.imin.iminapi.audience.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Platform-wide identity record. One row per normalized email address.
 * Created on first purchase; survives DSAR erasure from individual orgs as long
 * as at least one other org still references this consumer.
 *
 * <p>normalized_email = lower(trim(raw_email)) — enforced via {@link com.imin.iminapi.audience.service.EmailNormalizer}.
 */
@Entity
@Table(name = "consumers")
@Getter
@Setter
public class Consumer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "consumer_id")
    private UUID consumerId;

    @Column(name = "normalized_email", nullable = false, unique = true, length = 254)
    private String normalizedEmail;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
