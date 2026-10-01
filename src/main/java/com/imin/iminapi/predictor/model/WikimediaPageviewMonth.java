package com.imin.iminapi.predictor.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** User pageviews of one Wikipedia article in one month (V164), synced from Wikimedia Pageviews (CC0). */
@Entity
@Table(name = "wikimedia_pageviews_month")
@Getter
@Setter
public class WikimediaPageviewMonth {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Language edition, e.g. {@code fr.wikipedia}. */
    @Column(nullable = false, length = 32)
    private String project;

    /** Canonical title with underscores. */
    @Column(nullable = false, length = 255)
    private String article;

    /** First day of the month. */
    @Column(name = "view_month", nullable = false)
    private LocalDate viewMonth;

    @Column(nullable = false)
    private long views;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        syncedAt = syncedAt == null ? Times.nowMicros() : syncedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
