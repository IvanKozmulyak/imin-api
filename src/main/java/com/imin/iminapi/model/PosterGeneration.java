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
