package com.imin.iminapi.predictor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** The last poll of one transit source (V175): {@code syncedAt} moves only on an ok fetch, the attempt on every one. */
@Entity
@Table(name = "transit_sync_state")
@Getter
@Setter
public class TransitSyncState {

    /** The IDFM PRIM traffic-message source id, shared by both V175 tables and sources.yaml {@code syncSource}. */
    public static final String IDFM_PRIM = "idfm-prim";
    /** The IDFM stops reference source id (V176), shared by {@code transit_stop} and sources.yaml {@code syncSource}. */
    public static final String IDFM_STOPS = "idfm-stops";

    @Id
    @Column(length = 16)
    private String source;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "feed_updated_at")
    private Instant feedUpdatedAt;

    @Column(name = "disruption_count")
    private Integer disruptionCount;

    /** {@code ck_transit_sync_state_status}: ok, failed, unusable, rejected_key, rate_limited. */
    @Column(name = "last_status", nullable = false, length = 16)
    private String lastStatus;

    @Column(name = "last_attempt_at", nullable = false)
    private Instant lastAttemptAt;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        syncedAt = micros(syncedAt);
        feedUpdatedAt = micros(feedUpdatedAt);
        lastAttemptAt = micros(lastAttemptAt);
    }

    private static Instant micros(Instant i) {
        return i == null ? null : i.truncatedTo(ChronoUnit.MICROS);
    }
}
