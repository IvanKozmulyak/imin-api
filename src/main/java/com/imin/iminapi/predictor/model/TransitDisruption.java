package com.imin.iminapi.predictor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One published traffic message (V175), stored whole per poll by PrimWriter. Internal derived data under the
 * Licence Mobilités: read by the date check only, never exported raw.
 */
@Entity
@Table(name = "transit_disruption")
@Getter
@Setter
public class TransitDisruption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** {@code ck_transit_disruption_source}: idfm-prim. */
    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "disruption_id", nullable = false, length = 64)
    private String disruptionId;

    @Column(length = 32)
    private String cause;

    @Column(length = 32)
    private String severity;

    /** {@code ck_transit_disruption_kind}: strike, works, other. */
    @Column(nullable = false, length = 8)
    private String kind;

    @Column(length = 500)
    private String title;

    /** JSON {@code [{ref,label,mode,level}]}, level line or stop. */
    @Column(name = "lines_json", nullable = false, columnDefinition = "TEXT")
    private String linesJson;

    /** JSON {@code [{begin,end}]}, UTC ISO instants. */
    @Column(name = "periods_json", nullable = false, columnDefinition = "TEXT")
    private String periodsJson;

    @Column(name = "first_begin", nullable = false)
    private Instant firstBegin;

    @Column(name = "last_end", nullable = false)
    private Instant lastEnd;

    @Column(name = "last_update")
    private Instant lastUpdate;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        firstBegin = micros(firstBegin);
        lastEnd = micros(lastEnd);
        lastUpdate = micros(lastUpdate);
        syncedAt = micros(syncedAt);
    }

    private static Instant micros(Instant i) {
        return i == null ? null : i.truncatedTo(ChronoUnit.MICROS);
    }
}
