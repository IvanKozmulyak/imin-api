package com.imin.iminapi.audienceplan.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** One shown class × genre-fit segment of a plan, as counts and ranges; never who is in it. */
@Entity
@Table(name = "audience_plan_segments")
@Getter
@Setter
public class AudiencePlanSegment {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(nullable = false)
    private int position;

    @Column(name = "class", nullable = false, length = 16)
    private String classKey;

    @Column(name = "genre_fit", nullable = false, length = 8)
    private String genreFit;

    @Column(nullable = false)
    private int mailable;

    @Column(name = "rate_low", nullable = false)
    private double rateLow;

    @Column(name = "rate_mid", nullable = false)
    private double rateMid;

    @Column(name = "rate_high", nullable = false)
    private double rateHigh;

    @Column(name = "tickets_per_order", nullable = false)
    private double ticketsPerOrder;

    @Column(name = "expected_low", nullable = false)
    private int expectedLow;

    @Column(name = "expected_mid", nullable = false)
    private int expectedMid;

    @Column(name = "expected_high", nullable = false)
    private int expectedHigh;

    @Column(nullable = false, length = 8)
    private String confidence;

    /** JSON object of the class rule bounds and genre fit behind the segment. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;
}
