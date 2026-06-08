package com.imin.iminapi.dto;

import java.util.List;

/**
 * A per-vibe style card — sampling pools, palette, and the per-vibe variant plan / human policy
 * extracted from that vibe's reference posters. Loaded at startup by {@code StyleCardLibrary}.
 * Drives {@code CreativeDirectionSampler}, the art-director prompt, and the style-adherence gate.
 *
 * <p>The 10-arg constructor is retained for back-compat: it defaults to {@link HumanPolicy#REQUIRED},
 * a null {@link HumanStyle}, empty scene/abstract pools, and the legacy people/object/typographic plan.
 *
 * @param heroSubjectsScene    concrete rendered scene/landscape heroes (new mode)
 * @param heroSubjectsAbstract concrete abstract-graphic/texture-field heroes (new mode)
 * @param humanPolicy          whether a human hero is required / optional / rare / forbidden
 * @param humanStyle           how a human, when present, is rendered (null ⇒ photographic)
 * @param variantPlan          the ordered 3 hero-mode slots this vibe renders
 */
public record StyleCard(
        String vibeId,
        String medium,
        List<Rgb> palette,
        List<String> heroSubjectsPeople,
        List<String> heroSubjectsObject,
        List<String> heroSubjectsScene,
        List<String> heroSubjectsAbstract,
        List<String> compositions,
        List<String> accents,
        List<String> paletteTwists,
        List<String> typeTreatments,
        List<String> examplePrompts,
        HumanPolicy humanPolicy,
        HumanStyle humanStyle,
        List<VariantSlot> variantPlan
) {
    /** Legacy 10-arg constructor: empty new pools, REQUIRED policy, null style, legacy plan. */
    public StyleCard(
            String vibeId,
            String medium,
            List<Rgb> palette,
            List<String> heroSubjectsPeople,
            List<String> heroSubjectsObject,
            List<String> compositions,
            List<String> accents,
            List<String> paletteTwists,
            List<String> typeTreatments,
            List<String> examplePrompts) {
        this(vibeId, medium, palette, heroSubjectsPeople, heroSubjectsObject,
                List.of(), List.of(),
                compositions, accents, paletteTwists, typeTreatments, examplePrompts,
                HumanPolicy.REQUIRED, null, defaultPlan());
    }

    /** The legacy hero-mode plan: people, object, typographic. */
    public static List<VariantSlot> defaultPlan() {
        return List.of(
                new VariantSlot(HeroType.PEOPLE),
                new VariantSlot(HeroType.OBJECT),
                new VariantSlot(HeroType.TYPOGRAPHIC));
    }

    /**
     * The subject pool for a hero mode. TYPOGRAPHIC → empty (type is the image; it draws only
     * accent/texture). Null pools normalize to empty.
     */
    public List<String> heroSubjectsFor(HeroType type) {
        return switch (type) {
            case PEOPLE -> nz(heroSubjectsPeople);
            case OBJECT -> nz(heroSubjectsObject);
            case SCENE -> nz(heroSubjectsScene);
            case ABSTRACT_GRAPHIC -> nz(heroSubjectsAbstract);
            case TYPOGRAPHIC -> List.of();
        };
    }

    private static List<String> nz(List<String> list) {
        return list == null ? List.of() : list;
    }
}
