package com.imin.iminapi.dto;

import java.util.List;

/**
 * One RGB triple from a vibe's style-card palette (0–255 per channel). Used to build Recraft
 * {@code controls.colors} for curated photo-led vibes.
 */
public record Rgb(int r, int g, int b) {

    /** Build from a YAML list {@code [r, g, b]}; tolerates extra/short lists by clamping. */
    public static Rgb of(List<? extends Number> triple) {
        if (triple == null || triple.isEmpty()) return new Rgb(0, 0, 0);
        int r = clamp(triple.size() > 0 ? triple.get(0).intValue() : 0);
        int g = clamp(triple.size() > 1 ? triple.get(1).intValue() : 0);
        int b = clamp(triple.size() > 2 ? triple.get(2).intValue() : 0);
        return new Rgb(r, g, b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
