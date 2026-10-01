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
 * Weekly count of genre events in a city (V162), re-derived by OpenEventsWriter from open_event_occurrence:
 * distinct (normalised title, night) pairs of the ISO week (Monday start) whose genre keys contain the family.
 * {@code sourcesJson} lists every source counted, with 0 too, so the row records coverage as well as credit:
 * {@code [{"source":"openagenda","licence":"Licence Ouverte 2.0","events":3},{"source":"quefaireaparis","licence":"ODbL 1.0","events":0}]}.
 */
@Entity
@Table(name = "genre_week_count")
@Getter
@Setter
public class GenreWeekCount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "city_key", nullable = false, length = 100)
    private String cityKey;

    @Column(name = "genre_family", nullable = false, length = 64)
    private String genreFamily;

    /** Empty string = the whole genre family. */
    @Column(name = "sub_genre", nullable = false, length = 64)
    private String subGenre = "";

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "sources_json", nullable = false, columnDefinition = "TEXT")
    private String sourcesJson = "[]";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPersist() {
        updatedAt = updatedAt == null ? Times.nowMicros() : updatedAt.truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Times.nowMicros();
    }
}
