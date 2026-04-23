package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;

@Entity
@Table(name = "poster_variants")
@Getter
@Setter
public class PosterVariantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generation_id", nullable = false)
    private PosterGeneration posterGeneration;

    @Column(name = "variant_style", nullable = false, length = 32)
    private String variantStyle;

    @Column(name = "ideogram_prompt", columnDefinition = "TEXT", nullable = false)
    private String ideogramPrompt;

    @Column(name = "reference_images_used", columnDefinition = "TEXT")
    private String referenceImagesUsed;

    @Column(name = "seed")
    private Long seed;

    @Column(name = "raw_url", columnDefinition = "TEXT")
    private String rawUrl;

    @Column(name = "final_url", columnDefinition = "TEXT")
    private String finalUrl;

    @Enumerated(STRING)
    @Column(nullable = false, length = 20)
    private PosterVariantStatus status;

    @Column(name = "ideogram_cost_eur", precision = 10, scale = 4)
    private BigDecimal ideogramCostEur;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;
}
