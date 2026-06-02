package com.imin.iminapi.dto;

import java.util.List;

/**
 * One poster "vibe" preset — a hand-authored, IP-safe structured style spec mapped to music
 * genres. The building blocks (visualStyle, palette, typography, composition, moodTags, avoid)
 * are assembled into the image-generation prompt; {@code references} points at curated reference
 * flyers used to condition the model; {@code styleId} holds a provider's reusable trained-style id
 * once trained; {@code layoutTemplate} selects the per-vibe text-overlay layout.
 *
 * @param textOnly true when no curated reference images exist yet (runs from the structured
 *                 preset alone until references are curated) — keeps the startup guard from
 *                 treating an empty reference list as a misconfiguration.
 */
public record Vibe(
        String id,
        String name,
        List<String> genres,
        String visualStyle,
        List<String> palette,
        String typography,
        String composition,
        List<String> moodTags,
        List<String> avoid,
        String modelRoute,
        List<String> references,
        String styleId,
        String layoutTemplate,
        boolean textOnly
) {}
