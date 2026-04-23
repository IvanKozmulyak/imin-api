package com.imin.iminapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "style_reference_analysis")
@Getter
@Setter
public class StyleReferenceAnalysis {

    @Id
    @Column(name = "sub_style_tag", length = 64)
    private String subStyleTag;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String descriptor;

    @Column(name = "image_signature", nullable = false, length = 64)
    private String imageSignature;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;

    @PrePersist
    void prePersist() {
        if (analyzedAt == null) {
            analyzedAt = LocalDateTime.now();
        }
    }
}
