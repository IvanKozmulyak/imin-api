package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;

@Entity
@Table(name = "poster_generations")
@Getter
@Setter
public class PosterGeneration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "generated_event_id", nullable = false)
    private UUID generatedEventId;

    @Column(name = "organizer_id")
    private UUID organizerId;

    @Enumerated(STRING)
    @Column(nullable = false, length = 20)
    private PosterGenerationStatus status;

    @Column(name = "sub_style_tag", length = 64)
    private String subStyleTag;

    /**
     * The creative seed for this generation. Seeds the {@code CreativeDirectionSampler} so the run's
     * three creative directions are reproducible (same seed → same directions) yet vary across runs.
     */
    @Column(name = "creative_seed")
    private Long creativeSeed;

    /**
     * Resolved brand stamped at creation: JSON {colors, logoUrl, logoOn} or NULL when brandless.
     * The corrective remix path re-reads THIS snapshot, not live org state, so a poster remixed
     * after a rebrand doesn't silently mix old prompt colours with a new logo.
     */
    @Column(name = "brand_snapshot", columnDefinition = "TEXT")
    private String brandSnapshot;

    /** The DJ photo URL this generation rendered with, or NULL. Regenerate reads THIS, not the live event. */
    @Column(name = "dj_photo_url", columnDefinition = "TEXT")
    private String djPhotoUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "raw_ready_at")
    private LocalDateTime rawReadyAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "posterGeneration", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PosterVariantEntity> variants = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
