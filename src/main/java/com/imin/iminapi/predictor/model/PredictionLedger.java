package com.imin.iminapi.predictor.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One rendered prediction (spec §5, §7.2) — the append-only audit / evaluation / training
 * record. Written BEFORE the prediction reaches any surface (write-before-render contract,
 * see {@code PredictionLedgerService}). Mutated only by the monthly scoring job, which fills
 * the outcome-join columns once the event completes.
 */
@Entity
@Table(name = "prediction_ledger")
@Getter
@Setter
public class PredictionLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PredictionSurface surface;

    /** 0 = LLM-reasoned, 1 = deterministic pacing, 2 = learned model (§7.1). */
    @Column(nullable = false)
    private short stage;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    /** Prompt semver — a prompt change is a version bump or eval comparability dies silently (§7.3). */
    @Column(name = "prompt_version", nullable = false, length = 32)
    private String promptVersion;

    @Column(name = "input_snapshot_hash", nullable = false, length = 64)
    private String inputSnapshotHash;

    /** {ids:[...], clusterSize, relaxation} — the comparables the render leaned on. */
    @Column(name = "comparables_json", nullable = false, columnDefinition = "TEXT")
    private String comparablesJson = "{}";

    /** The full PredictionResult rendered (banded probability, ranges, factors, recommendations). */
    @Column(name = "output_json", nullable = false, columnDefinition = "TEXT")
    private String outputJson = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    // ---- outcome-join columns (filled by PredictionScoringJob at event completion) ----
    @Column(name = "actual_sold")
    private Integer actualSold;

    @Column(name = "actual_attendance")
    private Integer actualAttendance;

    @Column(name = "outcome_joined_at")
    private Instant outcomeJoinedAt;

    @Column(name = "brier_component")
    private BigDecimal brierComponent;

    @Column(name = "ape")
    private BigDecimal ape;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        if (createdAt != null) createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        if (outcomeJoinedAt != null) outcomeJoinedAt = outcomeJoinedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
