package com.imin.iminapi.predictor.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A dismissal or execution of one recommendation from a ledger render (spec §4.3).
 * A child of {@link PredictionLedger}: a single render carries up to 3 recommendations,
 * each independently actionable across sessions, so feedback is a row per event — not a
 * column on the render. {@code recommendationId} names the specific recommendation inside
 * the render's {@code output_json}.
 */
@Entity
@Table(name = "prediction_feedback")
@Getter
@Setter
public class PredictionFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ledger_id", nullable = false)
    private UUID ledgerId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "recommendation_id", nullable = false, length = 128)
    private String recommendationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_type", nullable = false, length = 16)
    private FeedbackType feedbackType;

    /**
     * Dismissal fingerprint (V74, task 86cav47a5): hash(actionType + tier ref + priceMinor
     * bucket) of the recommendation as it existed in the targeted render. Set on DISMISSED /
     * RESTORED rows (compute-on-write); null on EXECUTED and pre-V74 rows. Serve-time filtering
     * suppresses a currently-rendered recommendation whose fingerprint matches a row whose
     * latest state for that fingerprint is DISMISSED.
     */
    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void truncateTimestamps() {
        if (createdAt != null) createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }
}
