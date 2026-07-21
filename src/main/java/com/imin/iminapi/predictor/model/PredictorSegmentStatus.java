package com.imin.iminapi.predictor.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Per-segment accuracy status + downgrade tripwire state (V72, spec §5). One row per
 * genre_family × capacity_band segment that has scored events. Written by the monthly
 * {@code PredictionScoringJob}; read at scoring time to apply {@code languageTierOverride}.
 *
 * <p>Override values: {@link #OVERRIDE_DROP_ONE} (speak one tier lower),
 * {@link #OVERRIDE_QUALITATIVE} (drop numeric attendance/revenue ranges),
 * {@link #OVERRIDE_DROP_ONE_QUALITATIVE} (both). Downgrades automatic, upgrades manual
 * only — see the V72 migration header for the manual-upgrade SQL.
 */
@Entity
@Table(name = "predictor_segment_status")
@Getter
@Setter
public class PredictorSegmentStatus {

    public static final String OVERRIDE_DROP_ONE = "DROP_ONE";
    public static final String OVERRIDE_QUALITATIVE = "QUALITATIVE";
    public static final String OVERRIDE_DROP_ONE_QUALITATIVE = "DROP_ONE_QUALITATIVE";

    /** '{@code <genre_family>|<capacity_band>}'. */
    @Id
    @Column(name = "segment_key", length = 160)
    private String segmentKey;

    @Column(name = "scored_count", nullable = false)
    private int scoredCount;

    @Column
    private BigDecimal brier;

    @Column(name = "base_rate_brier")
    private BigDecimal baseRateBrier;

    @Column
    private BigDecimal mape;

    @Column(name = "language_tier_override", length = 32)
    private String languageTierOverride;

    @Column(name = "downgraded_at")
    private Instant downgradedAt;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Times.nowMicros();

    /** Build the canonical segment key. Null parts become "?" (still a valid, stable key). */
    public static String key(String genreFamily, CapacityBand band) {
        return (genreFamily == null ? "?" : genreFamily) + "|" + (band == null ? "?" : band.name());
    }

    public boolean dropsOneTier() {
        return OVERRIDE_DROP_ONE.equals(languageTierOverride)
                || OVERRIDE_DROP_ONE_QUALITATIVE.equals(languageTierOverride);
    }

    public boolean qualitativeOnly() {
        return OVERRIDE_QUALITATIVE.equals(languageTierOverride)
                || OVERRIDE_DROP_ONE_QUALITATIVE.equals(languageTierOverride);
    }

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        if (updatedAt != null) updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
        if (downgradedAt != null) downgradedAt = downgradedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
