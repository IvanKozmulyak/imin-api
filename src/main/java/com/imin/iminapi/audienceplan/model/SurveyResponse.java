package com.imin.iminapi.audienceplan.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** One post-event survey answer. Never linked to a person, even when the consent box was ticked. */
@Entity
@Table(name = "survey_responses")
@Getter
@Setter
public class SurveyResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "home_commune", length = 80)
    private String homeCommune;

    /** JSON array of genre bucket keys. */
    @Column(name = "other_genres", columnDefinition = "TEXT")
    private String otherGenres;

    @Column(name = "heard_from", length = 16)
    private String heardFrom;

    @Column(name = "age_band", length = 8)
    private String ageBand;

    @Column(name = "first_time")
    private Boolean firstTime;

    @Column(name = "notice_version", nullable = false, length = 32)
    private String noticeVersion;

    @Column(nullable = false, length = 8)
    private String locale;

    /** UTC day only: a time of day could re-link the answer to the survey consent record. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void truncateToDay() {
        createdAt = (createdAt == null ? Instant.now() : createdAt).truncatedTo(ChronoUnit.DAYS);
    }
}
