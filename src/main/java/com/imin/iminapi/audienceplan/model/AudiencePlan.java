package com.imin.iminapi.audienceplan.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One computed audience plan for an event; the newest row without {@code supersededBy} is current. */
@Entity
@Table(name = "audience_plans")
@Getter
@Setter
public class AudiencePlan {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 8)
    private String mode;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "target_pct", nullable = false)
    private int targetPct;

    @Column(name = "target_tickets", nullable = false)
    private int targetTickets;

    @Column(name = "tickets_per_order", nullable = false)
    private double ticketsPerOrder;

    /** JSON array of class keys. */
    @Column(name = "excluded_segments", nullable = false, columnDefinition = "TEXT")
    private String excludedSegments;

    @Column(nullable = false)
    private int mailable;

    @Column(name = "expected_low")
    private Integer expectedLow;

    @Column(name = "expected_mid")
    private Integer expectedMid;

    @Column(name = "expected_high")
    private Integer expectedHigh;

    @Column(name = "coverage_low")
    private Double coverageLow;

    @Column(name = "coverage_mid")
    private Double coverageMid;

    @Column(name = "coverage_high")
    private Double coverageHigh;

    @Column(nullable = false, length = 8)
    private String verdict;

    @Column(name = "gap_low", nullable = false)
    private int gapLow;

    @Column(name = "gap_high", nullable = false)
    private int gapHigh;

    @Column(name = "reach_needed", nullable = false, columnDefinition = "TEXT")
    private String reachNeeded;

    @Column(name = "gap_exceeds_tribe")
    private Boolean gapExceedsTribe;

    @Column(name = "small_groups_not_shown", nullable = false)
    private int smallGroupsNotShown;

    @Column(name = "other_genre_invited", nullable = false)
    private boolean otherGenreInvited;

    @Column(name = "other_genre_held_back", nullable = false)
    private int otherGenreHeldBack;

    /** JSON object reason → count. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String exclusions;

    @Column(name = "today_date", nullable = false)
    private LocalDate todayDate;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "launch_date", nullable = false)
    private LocalDate launchDate;

    @Column(name = "d3_date")
    private LocalDate d3Date;

    @Column(name = "event_started", nullable = false)
    private boolean eventStarted;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String actions;

    @Column(name = "logic_version", nullable = false)
    private int logicVersion;

    @Column(name = "priors_version", nullable = false)
    private int priorsVersion;

    @Column(name = "calibration_version", nullable = false)
    private int calibrationVersion;

    @Column(name = "inputs_hash", nullable = false, length = 64)
    private String inputsHash;

    @Column(name = "portrait_id")
    private UUID portraitId;

    /** JSON object locale → summary; null until one is generated. */
    @Column(columnDefinition = "TEXT")
    private String summaries;

    @Column(name = "model_id", length = 128)
    private String modelId;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "cost_usd", precision = 8, scale = 4)
    private BigDecimal costUsd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "superseded_by")
    private UUID supersededBy;
}
