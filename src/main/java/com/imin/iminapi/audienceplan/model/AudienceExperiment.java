package com.imin.iminapi.audienceplan.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One arm (holdout, launch, d3, ...) of an invited plan segment for one event. */
@Entity
@Table(name = "audience_experiments")
@Getter
@Setter
public class AudienceExperiment {

    public static final String ARM_HOLDOUT = "holdout";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "plan_segment_id")
    private UUID planSegmentId;

    /** holdout | launch | d3 | slump | two_emails */
    @Column(nullable = false, length = 16)
    private String arm;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(nullable = false)
    private int members;

    @Column(nullable = false)
    private long seed;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
