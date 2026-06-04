package com.imin.iminapi.dto;

import java.util.List;

/**
 * A per-vibe style card — the sampling pools and palette extracted from that vibe's real reference
 * posters. Authored from the references (see the {@code @Profile("tools")} StyleCardGenerator) and
 * committed as {@code classpath:vibes/style-cards/{vibeId}.yaml}; loaded at startup by
 * {@code StyleCardLibrary}. Drives the {@code CreativeDirectionSampler} (run-to-run variation) and
 * the art-director prompt, and supplies the palette for Recraft curated rendering + the
 * style-adherence validation gate.
 *
 * @param medium             dominant medium across the references: photo | illustration | collage | mixed
 * @param palette            3–4 dominant RGB triples sampled from the references
 * @param heroSubjectsPeople 4–6 concrete, photographable human-hero scenes
 * @param heroSubjectsObject 4–6 concrete, photographable non-human hero objects/scenes
 * @param compositions       5–8 layout archetypes (where the hero sits, where type goes, the crop)
 * @param accents            4–6 media/texture twists actually present (grain, flash, photocopy…)
 * @param paletteTwists      human-readable palette variants for the prompt
 * @param typeTreatments     typography treatments seen in the references
 * @param examplePrompts     2–3 complete reference ideogram prompts (the few-shot anchor pool)
 */
public record StyleCard(
        String vibeId,
        String medium,
        List<Rgb> palette,
        List<String> heroSubjectsPeople,
        List<String> heroSubjectsObject,
        List<String> compositions,
        List<String> accents,
        List<String> paletteTwists,
        List<String> typeTreatments,
        List<String> examplePrompts
) {
    /**
     * The subject pool for a hero type. PEOPLE → people pool, OBJECT → object pool,
     * TYPOGRAPHIC → empty (type is the image; it draws only accent/texture).
     */
    public List<String> heroSubjectsFor(HeroType type) {
        if (type == HeroType.PEOPLE) return heroSubjectsPeople == null ? List.of() : heroSubjectsPeople;
        if (type == HeroType.OBJECT) return heroSubjectsObject == null ? List.of() : heroSubjectsObject;
        return List.of();
    }
}
