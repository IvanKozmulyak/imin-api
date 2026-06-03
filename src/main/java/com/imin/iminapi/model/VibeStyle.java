package com.imin.iminapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A trained, reusable image-provider style for one vibe — the {@code style_id}
 * returned by a provider's style-training endpoint (e.g. Recraft {@code POST
 * /styles}). The orchestrator resolves a vibe's {@code style_id} from this table
 * at generation time, falling back to {@code Vibe.styleId()} from vibes.yaml when
 * no trained row exists. Keyed by {@code (vibe_id, provider)}.
 */
@Entity
@Table(name = "vibe_style")
@IdClass(VibeStyle.Key.class)
@Getter
@Setter
public class VibeStyle {

    @Id
    @Column(name = "vibe_id", length = 64)
    private String vibeId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 32)
    private ImageProvider provider;

    @Column(name = "style_id", nullable = false, length = 128)
    private String styleId;

    @Column(name = "trained_at", nullable = false)
    private LocalDateTime trainedAt;

    @PrePersist
    void prePersist() {
        if (trainedAt == null) {
            trainedAt = LocalDateTime.now();
        }
    }

    /** Composite primary key for {@link VibeStyle}. Field names must match the @Id fields. */
    @Getter
    @Setter
    public static class Key implements java.io.Serializable {
        private String vibeId;
        private ImageProvider provider;

        public Key() {}

        public Key(String vibeId, ImageProvider provider) {
            this.vibeId = vibeId;
            this.provider = provider;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return java.util.Objects.equals(vibeId, key.vibeId) && provider == key.provider;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(vibeId, provider);
        }
    }
}
