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

    /**
     * Derived merge key for the genre (V82) — {@code lower(collapse(trim(genre)))}, computed
     * by {@link com.imin.iminapi.util.EventNormalization#genreKey(String)}.
     *
     * <p>DERIVED, NEVER AUTHORED — same contract as {@link #venueCityKey}. Recomputed from
     * {@code genre} in {@link #onPersist()} and {@link #onUpdate()}, so it cannot drift
     * whichever path wrote the row.
     *
     * <p>The buyer's genre facet groups on it and {@code ?genre=} matches it, so {@code Techno}
     * and {@code techno} are one chip whose count equals its own result page. The display string
     * stays as the organizer typed it: both frontends print {@code genre} verbatim, and the
     * organizer wizard picks it out of a closed Title-Case list that a folded value cannot match.
     */
    @Column(name = "genre_key", nullable = false)
    private String genreKey = "";

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

    /**
     * Derived merge key for the city (V82) — {@code lower(collapse(trim(venueCity)))}, computed
     * by {@link com.imin.iminapi.util.EventNormalization#cityKey(String)}.
     *
     * <p>DERIVED, NEVER AUTHORED. It is recomputed from {@code venueCity} in {@link #onPersist()}
     * and {@link #onUpdate()}, so it cannot drift no matter which path wrote the row — service,
     * repository, fixture. Setting it by hand is pointless; the callback overwrites it.
     *
     * <p>It exists because the buyer's city facet and the {@code ?city=} listing filter both group
     * and match on it: {@code Metz}, {@code METZ} and {@code  metz } are one chip whose count is
     * exactly what tapping it returns. The display string stays as the organizer typed it —
     * case-folding city names destroys {@code 's-Hertogenbosch} and {@code L'Aquila}.
     */
    @Column(name = "venue_city_key", nullable = false)
    private String venueCityKey = "";

    /**
     * Venue point (V80), nullable by design and always set as a PAIR.
     *
     * <p>Populated best-effort by {@code VenueGeocodingListener} after an address
     * write; null whenever geocoding is disabled, the provider had no answer, or the
     * address is too thin to resolve. Null is not an error state — the buyer page
     * falls back to the maps deep link built from the address strings.
     */
    @Column(name = "venue_latitude")
    private Double venueLatitude;

    @Column(name = "venue_longitude")
    private Double venueLongitude;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description = "";

    @Column(name = "poster_url", columnDefinition = "TEXT")
    private String posterUrl;

    @Column(name = "video_url", columnDefinition = "TEXT")
    private String videoUrl;

    @Column(name = "dj_photo_url", columnDefinition = "TEXT")
    private String djPhotoUrl;

    @Column(nullable = false)
    private int sold = 0;

    @Column(name = "revenue_minor", nullable = false)
    private long revenueMinor = 0;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

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

    /**
     * AI-provenance stamps (V71, predictor spec §6.1). Tri-state on purpose:
     * TRUE = verified AI origin, FALSE = verified manual origin, NULL = unknown.
     * NO-FABRICATION: absence of a provenance signal stays NULL — it is never
     * collapsed to FALSE (see V71__event_ai_provenance.sql for the seam rules).
     */
    @Column(name = "concept_ai_generated")
    private Boolean conceptAiGenerated;

    @Column(name = "poster_ai_generated")
    private Boolean posterAiGenerated;

    @PrePersist
    void onPersist() {
        venueCityKey = com.imin.iminapi.util.EventNormalization.cityKey(venueCity);
        genreKey = com.imin.iminapi.util.EventNormalization.genreKey(genre);
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt == null ? Times.nowMicros() : updatedAt.truncatedTo(ChronoUnit.MICROS);
        if (publishedAt != null) publishedAt = publishedAt.truncatedTo(ChronoUnit.MICROS);
        if (deletedAt != null) deletedAt = deletedAt.truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void onUpdate() {
        venueCityKey = com.imin.iminapi.util.EventNormalization.cityKey(venueCity);
        genreKey = com.imin.iminapi.util.EventNormalization.genreKey(genre);
        updatedAt = Times.nowMicros();
        if (publishedAt != null) publishedAt = publishedAt.truncatedTo(ChronoUnit.MICROS);
        if (deletedAt != null) deletedAt = deletedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
