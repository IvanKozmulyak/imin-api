package com.imin.iminapi.dto;

import java.util.Locale;

/** How a human, when present, must be rendered for a vibe. */
public enum HumanStyle {
    /** Photorealistic person with correct anatomy. */
    PHOTOGRAPHIC,
    /** Motion-blur / halftone / silhouette — never a clean portrait. */
    ABSTRACTED,
    /** A sculpted/rendered material object (chrome, wireframe, posterized), not a photographed person. */
    FIGURE_AS_OBJECT;

    /** Parse the YAML token; null/unknown → null (caller treats null as photographic). */
    public static HumanStyle fromWire(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "abstracted" -> ABSTRACTED;
            case "figure_as_object" -> FIGURE_AS_OBJECT;
            case "photographic" -> PHOTOGRAPHIC;
            default -> null;
        };
    }
}
