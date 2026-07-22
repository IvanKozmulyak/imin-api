package com.imin.iminapi.predictor.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A persisted pacing curve for one comparable segment (spec §7 Stage 1, task 86cav479d).
 * Written by the daily {@code PacingCurveService.rebuildAll}; read by the live re-forecast.
 *
 * <p>{@code pointsJson} is the median + P25/P75 spread of normalized completed-event
 * trajectories, sorted by {@code daysOut} DESC. It is a cross-org aggregate of %-SHAPES over
 * {@code >= min-curve-events} events — no figures, no ids, nothing attributable to a single
 * foreign event (privacy §6.4). {@code segmentKey} carries its own relaxation granularity so
 * the three ladder rungs never collide (see V73).
 */
@Entity
@Table(name = "pacing_curves")
@Getter
@Setter
public class PacingCurve {

    @Id
    @Column(name = "segment_key", length = 200)
    private String segmentKey;

    @Column(nullable = false, length = 32)
    private String relaxation;

    @Column(name = "points_json", nullable = false, columnDefinition = "TEXT")
    private String pointsJson = "[]";

    @Column(name = "events_count", nullable = false)
    private int eventsCount;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        computedAt = computedAt == null ? Times.nowMicros() : computedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
