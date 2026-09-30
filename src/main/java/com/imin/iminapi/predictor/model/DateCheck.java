package com.imin.iminapi.predictor.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** One "Check a date" request: the organizer's inputs and the run's state (V162). */
@Entity
@Table(name = "date_check")
@Getter
@Setter
public class DateCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(name = "genre_family", nullable = false, length = 64)
    private String genreFamily;

    @Column(name = "sub_genre", length = 64)
    private String subGenre;

    private Integer capacity;

    @Column(name = "price_minor")
    private Long priceMinor;

    @Column(length = 32)
    private String format;

    @Column(name = "start_hour")
    private Short startHour;

    @Column(name = "end_hour")
    private Short endHour;

    @Column(name = "lineup_json", columnDefinition = "TEXT")
    private String lineupJson;

    @Column(name = "known_events_json", columnDefinition = "TEXT")
    private String knownEventsJson;

    @Column(name = "assumptions_json", nullable = false, columnDefinition = "TEXT")
    private String assumptionsJson = "[]";

    @Column(nullable = false)
    private boolean research = false;

    /** pending | running | done | partial | failed. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "question_bank_version", nullable = false, length = 32)
    private String questionBankVersion;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPersist() {
        createdAt = createdAt == null ? Times.nowMicros() : createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt == null ? Times.nowMicros() : updatedAt.truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Times.nowMicros();
    }
}
