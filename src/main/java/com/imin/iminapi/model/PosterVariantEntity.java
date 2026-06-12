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

    // Holds the variant's hero_type (people | object | typographic). Column name kept as
    // variant_style for backward compatibility with the existing schema/OpenAPI surface.
    @Column(name = "variant_style", nullable = false, length = 32)
    private String variantStyle;

    /** The sampled {@code CreativeDirection} for this variant, serialized as JSON, for debugging. */
    @Column(name = "creative_direction_json", columnDefinition = "TEXT")
    private String creativeDirectionJson;

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

    /** Final gate outcome: ACCEPTED (both gates passed) or BEST_EFFORT (accepted despite a gate). */
    @Column(name = "validation_verdict", length = 16)
    private String validationVerdict;

    /** Per-attempt validation journal as JSON: [{attempt,seed,mode,text,style}]. */
    @Column(name = "validation_attempts_json", columnDefinition = "TEXT")
    private String validationAttemptsJson;

    /** Logo composite outcome, beside validation_verdict: NULL | 'APPLIED' | 'SKIPPED' | 'FAILED'. */
    @Column(name = "logo_composite_status", length = 16)
    private String logoCompositeStatus;
}
