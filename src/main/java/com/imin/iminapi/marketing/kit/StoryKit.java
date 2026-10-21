package com.imin.iminapi.marketing.kit;

import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One event's Instagram kit; {@code source_*} snapshot what the images show, {@code revision} orders remake vs caption. */
@Entity
@Table(name = "story_kits")
@Getter
@Setter
public class StoryKit {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "share_link_code", nullable = false, length = 12)
    private String shareLinkCode;

    @Column(nullable = false, length = 2)
    private String locale;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String assets;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String skipped;

    @Column(name = "ai_derived", nullable = false)
    private boolean aiDerived;

    @Column(name = "source_poster_url", nullable = false, columnDefinition = "TEXT")
    private String sourcePosterUrl;

    @Column(name = "source_starts_at")
    private Instant sourceStartsAt;

    @Column(name = "source_timezone", nullable = false)
    private String sourceTimezone;

    @Column(name = "source_venue_name", columnDefinition = "TEXT")
    private String sourceVenueName;

    @Column(name = "source_venue_city", nullable = false, columnDefinition = "TEXT")
    private String sourceVenueCity = "";

    // Written only by the targeted UPDATE in the repository, so a full-entity save never reverts it.
    @Column(columnDefinition = "TEXT", updatable = false)
    private String caption;

    @Column(name = "caption_ai_generated", updatable = false)
    private Boolean captionAiGenerated;

    @Column(name = "caption_created_at", updatable = false)
    private Instant captionCreatedAt;

    @Column(nullable = false)
    private int revision;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();
}
