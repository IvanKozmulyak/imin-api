package com.imin.iminapi.predictor.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One night of one open-data listing (V165) that matched a genre bucket or a local-event keyword.
 * Title and link only; {@code licence} is per row so ODbL rows stay separable from Licence Ouverte ones.
 */
@Entity
@Table(name = "open_event_occurrence")
@Getter
@Setter
public class OpenEventOccurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** {@code ck_open_event_occurrence_source}: openagenda, quefaireaparis, datatourisme. */
    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "source_event_id", nullable = false, length = 64)
    private String sourceEventId;

    @Column(name = "city_key", nullable = false, length = 100)
    private String cityKey;

    @Column(name = "night_date", nullable = false)
    private LocalDate nightDate;

    @Column(nullable = false, length = 255)
    private String title;

    /** Normalised title: cross-source dedup and recurrence grouping. */
    @Column(name = "title_key", nullable = false, length = 255)
    private String titleKey;

    @Column(nullable = false, length = 512)
    private String url;

    /** JSON array of genre bucket names, in bank order. */
    @Column(name = "genre_keys", nullable = false, columnDefinition = "TEXT")
    private String genreKeys = "[]";

    /** Matched a local city-wide event keyword (carnaval, braderie…). */
    @Column(nullable = false)
    private boolean community;

    /** {@code ck_open_event_occurrence_licence}: Licence Ouverte 2.0 or ODbL 1.0. */
    @Column(nullable = false, length = 32)
    private String licence;

    /** Per-row author credit where the source requires one; null for today's sources. */
    @Column(length = 255)
    private String credit;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        syncedAt = syncedAt == null ? Times.nowMicros() : syncedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
