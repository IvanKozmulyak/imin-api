package com.imin.iminapi.predictor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** One answered question for a candidate date, with its source (V162). */
@Entity
@Table(name = "date_check_finding")
@Getter
@Setter
public class DateCheckFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "date_check_date_id", nullable = false)
    private UUID dateCheckDateId;

    @Column(name = "question_id", nullable = false, length = 16)
    private String questionId;

    /** risk | opportunity. */
    @Column(nullable = false, length = 16)
    private String kind;

    /** found | clear | not_checked. */
    @Column(nullable = false, length = 16)
    private String status;

    /** 0..3; only structured/internal sources may reach 3. */
    @Column(nullable = false)
    private short strength;

    /** 1..3. */
    @Column(nullable = false)
    private short weight;

    /** structured | internal | web | organizer | input; only structured/internal may be a stop factor. */
    @Column(name = "source_kind", nullable = false, length = 16)
    private String sourceKind;

    /** night | week | month. */
    @Column(name = "time_window", nullable = false, length = 8)
    private String timeWindow;

    @Column(name = "stop_factor", nullable = false)
    private boolean stopFactor = false;

    @Column(name = "facts_json", nullable = false, columnDefinition = "TEXT")
    private String factsJson = "{}";

    @Column(length = 2048)
    private String url;

    @Column(length = 1000)
    private String quote;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        if (fetchedAt != null) fetchedAt = fetchedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
