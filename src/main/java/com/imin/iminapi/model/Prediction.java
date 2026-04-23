package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "predictions")
@Getter
@Setter
public class Prediction {
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(nullable = false)
    private int score;

    @Column(name = "range_low", nullable = false)
    private int rangeLow;

    @Column(name = "range_high", nullable = false)
    private int rangeHigh;

    @Column(name = "confidence_pct", nullable = false)
    private int confidencePct;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String insight;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;
}
