package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "events")
@Getter
@Setter
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name = "";

    @Column(nullable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventVisibility visibility = EventVisibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventStatus status = EventStatus.DRAFT;

    @Column(nullable = false)
    private String genre = "";

    @Column(nullable = false)
    private String type = "";

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(nullable = false)
    private String timezone = "UTC";

    @Column(name = "venue_name")
    private String venueName;
    @Column(name = "venue_street", nullable = false)
    private String venueStreet = "";
    @Column(name = "venue_city", nullable = false)
    private String venueCity = "";
    @Column(name = "venue_postal_code", nullable = false)
    private String venuePostalCode = "";
    @Column(name = "venue_country", length = 2)
    private String venueCountry;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description = "";

    @Column(name = "poster_url", columnDefinition = "TEXT")
    private String posterUrl;

    @Column(name = "video_url", columnDefinition = "TEXT")
    private String videoUrl;

    @Column(name = "cover_url", columnDefinition = "TEXT")
    private String coverUrl;

    @Column(nullable = false)
    private int sold = 0;

    @Column(name = "revenue_minor", nullable = false)
    private long revenueMinor = 0;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "squads_enabled", nullable = false)
    private boolean squadsEnabled = false;

    @Column(name = "min_squad_size", nullable = false)
    private int minSquadSize = 3;

    @Column(name = "squad_discount_pct", nullable = false)
    private int squadDiscountPct = 0;

    @Column(name = "on_sale_at")
    private Instant onSaleAt;

    @Column(name = "sale_closes_at")
    private Instant saleClosesAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Times.nowMicros();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void onPersist() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt == null ? Times.nowMicros() : updatedAt.truncatedTo(ChronoUnit.MICROS);
        if (publishedAt != null) publishedAt = publishedAt.truncatedTo(ChronoUnit.MICROS);
        if (deletedAt != null) deletedAt = deletedAt.truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Times.nowMicros();
        if (publishedAt != null) publishedAt = publishedAt.truncatedTo(ChronoUnit.MICROS);
        if (deletedAt != null) deletedAt = deletedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
