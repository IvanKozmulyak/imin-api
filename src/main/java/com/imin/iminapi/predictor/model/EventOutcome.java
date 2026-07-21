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
 * The event outcome record (spec §6.1) — one row per event, written in two passes:
 * <b>frozen at publish</b> (the pre-event snapshot the predictor could still act on)
 * and <b>finalized post-event</b> (the actual result). Keyed by {@code event_id}.
 *
 * <p>This record IS the Phase 1 "training-set logging" requirement made concrete
 * (§6.1). See {@code V68__event_outcomes.sql} for the honesty columns that are
 * deliberately NULL because the current data model can't supply them (venue type,
 * indoor/open-air, AI provenance, NPS).
 */
@Entity
@Table(name = "event_outcomes")
@Getter
@Setter
public class EventOutcome {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    // ----- frozen at publish: attributes -----
    @Column
    private String city;

    @Column(length = 2)
    private String country;

    /** NULL — events carry no venue-type classification (honesty column). */
    @Column(name = "venue_type", length = 64)
    private String venueType;

    /** events.genre — the fixed 8-bucket family taxonomy (V32). */
    @Column(name = "genre_family", length = 64)
    private String genreFamily;

    /** NULL — not derivable from any stored attribute (honesty column). */
    @Column(name = "indoor_open_air", length = 16)
    private String indoorOpenAir;

    @Column
    private Integer capacity;

    @Column(name = "event_date")
    private Instant eventDate;

    /** ISO day-of-week 1=Mon..7=Sun, in the event's timezone. */
    @Column(name = "day_of_week")
    private Short dayOfWeek;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Season season;

    @Enumerated(EnumType.STRING)
    @Column(name = "capacity_band", length = 8)
    private CapacityBand capacityBand;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    // ----- frozen at publish: structure & provenance -----
    @Column(name = "tier_structure_json", nullable = false, columnDefinition = "TEXT")
    private String tierStructureJson = "[]";

    @Column(name = "promo_config_json", nullable = false, columnDefinition = "TEXT")
    private String promoConfigJson = "[]";

    /** NULL — no schema link from a published event to a GeneratedEvent (honesty column). */
    @Column(name = "concept_ai_generated")
    private Boolean conceptAiGenerated;

    /** NULL — no schema link from a published event to a PosterGeneration (honesty column). */
    @Column(name = "poster_ai_generated")
    private Boolean posterAiGenerated;

    @Column(name = "organizer_tenure_days")
    private Integer organizerTenureDays;

    @Column(name = "prior_event_count")
    private Integer priorEventCount;

    @Column(name = "snapshot_reconstructed", nullable = false)
    private boolean snapshotReconstructed = false;

    @Column(name = "frozen_at", nullable = false)
    private Instant frozenAt = Times.nowMicros();

    // ----- finalized post-event (nullable until the finalize job runs) -----
    @Column(name = "sold_total")
    private Integer soldTotal;

    @Column(name = "sold_per_tier_json", columnDefinition = "TEXT")
    private String soldPerTierJson;

    @Column(name = "gross_revenue_minor")
    private Long grossRevenueMinor;

    @Column(name = "sell_out")
    private Boolean sellOut;

    @Column(name = "time_to_sell_out_hours")
    private Integer timeToSellOutHours;

    @Column(name = "refund_count")
    private Integer refundCount;

    @Column(name = "refund_rate")
    private BigDecimal refundRate;

    @Column
    private Integer attendance;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_source", length = 8)
    private AttendanceSource attendanceSource;

    @Column(name = "funnel_views")
    private Integer funnelViews;

    @Column(name = "funnel_checkout_starts")
    private Integer funnelCheckoutStarts;

    @Column(name = "funnel_paid")
    private Integer funnelPaid;

    @Column(name = "campaign_sends")
    private Integer campaignSends;

    /** NULL — the post-event survey does not exist yet (honesty column). */
    @Column
    private BigDecimal nps;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        if (frozenAt != null) frozenAt = frozenAt.truncatedTo(ChronoUnit.MICROS);
        if (eventDate != null) eventDate = eventDate.truncatedTo(ChronoUnit.MICROS);
        if (finalizedAt != null) finalizedAt = finalizedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
