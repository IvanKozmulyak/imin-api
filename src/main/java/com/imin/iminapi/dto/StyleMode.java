package com.imin.iminapi.dto;

/**
 * How a vibe is rendered by the image provider (Recraft).
 *
 * <ul>
 *   <li>{@link #TRAINED_STYLE_ID} — send the per-vibe trained {@code style_id} (illustration-led
 *       vibes whose look transfers well through a trained style).</li>
 *   <li>{@link #CURATED_SUBSTYLE} — send a curated {@code style=realistic_image} + {@code substyle}
 *       + {@code controls.colors} from the style-card palette (photo-led vibes, where a trained
 *       style on heterogeneous refs silently drops the base style and loses the photographic look).</li>
 * </ul>
 */
public enum StyleMode {
    TRAINED_STYLE_ID,
    CURATED_SUBSTYLE;

    /** Parse a vibes.yaml {@code style_mode} token; defaults to {@link #TRAINED_STYLE_ID} when blank/unknown. */
    public static StyleMode fromConfig(String raw) {
        if (raw == null) return TRAINED_STYLE_ID;
        return switch (raw.trim().toLowerCase()) {
            case "curated_substyle", "curated-substyle", "curated", "substyle" -> CURATED_SUBSTYLE;
            case "trained_style_id", "trained-style-id", "trained", "style_id" -> TRAINED_STYLE_ID;
            default -> TRAINED_STYLE_ID;
        };
    }
}
