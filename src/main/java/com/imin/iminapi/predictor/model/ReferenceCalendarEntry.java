package com.imin.iminapi.predictor.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A dated calendar fact (holiday, school break, fixture…) from a cited source (V162). */
@Entity
@Table(name = "reference_calendar")
@Getter
@Setter
public class ReferenceCalendarEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 2)
    private String country;

    /** Empty string = the whole country. */
    @Column(nullable = false, length = 16)
    private String region = "";

    @Column(name = "calendar_date", nullable = false)
    private LocalDate calendarDate;

    /** Last day of a range; null for a single day. */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** holiday | school | pont | dst | hijri | fixture. */
    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false)
    private String name;

    @Column(name = "source_url", nullable = false, length = 2048)
    private String sourceUrl;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        syncedAt = syncedAt == null ? Times.nowMicros() : syncedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
