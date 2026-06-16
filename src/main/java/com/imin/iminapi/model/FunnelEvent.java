package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per instrumented funnel occurrence (a buyer viewing the public event
 * page, or starting checkout). Append-only — never updated. See
 * V41__event_funnel_events.sql.
 */
@Entity
@Table(name = "event_funnel_events")
@Getter
@Setter
public class FunnelEvent {

    /** The two stages we instrument client-side. PAYMENTS_COMPLETED is derived from orders. */
    public static final String STAGE_PAGE_VIEW = "PAGE_VIEW";
    public static final String STAGE_CHECKOUT_START = "CHECKOUT_START";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 32)
    private String stage;

    @Column(name = "anon_id", nullable = false, length = 64)
    private String anonId;

    // UTM attribution (V43). All nullable — old beacon callers omit them.
    @Column(name = "utm_source", length = 128)
    private String utmSource;

    @Column(name = "utm_medium", length = 128)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 128)
    private String utmCampaign;

    @Column(name = "utm_content", length = 128)
    private String utmContent;

    @Column(name = "referrer_host", length = 255)
    private String referrerHost;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();
}
