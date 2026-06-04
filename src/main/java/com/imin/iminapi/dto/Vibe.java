package com.imin.iminapi.dto;

import java.util.List;

/**
 * One poster "vibe" preset — a hand-authored, IP-safe structured style spec mapped to music
 * genres. The building blocks (visualStyle, palette, typography, composition, moodTags, avoid)
 * are assembled into the image-generation prompt; {@code references} points at curated reference
 * flyers used to condition the model; {@code styleId} holds a provider's reusable trained-style id
 * once trained; {@code layoutTemplate} selects the per-vibe text-overlay layout.
 *
 * @param subject   one or two sentences naming the hero subject matter for this vibe (drawn from the
 *                  reference posters) — most vibes are image-led with a human or object hero. Injected
 *                  into the art-director prompt so the model renders a subject, not an empty texture plate.
 * @param styleMode how Recraft renders this vibe: {@link StyleMode#TRAINED_STYLE_ID} (illustration-led,
 *                  the trained style transfers the look) or {@link StyleMode#CURATED_SUBSTYLE} (photo-led,
 *                  a curated {@code realistic_image} substyle + palette controls).
 * @param substyle  the Recraft {@code realistic_image} substyle to send when {@code styleMode} is
 *                  {@link StyleMode#CURATED_SUBSTYLE} (e.g. {@code hard_flash}); null/ignored otherwise.
 * @param textOnly  true when no curated reference images exist yet (runs from the structured
 *                  preset alone until references are curated) — keeps the startup guard from
 *                  treating an empty reference list as a misconfiguration.
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
        boolean textOnly,
        String subject,
        StyleMode styleMode,
        String substyle
) {}
