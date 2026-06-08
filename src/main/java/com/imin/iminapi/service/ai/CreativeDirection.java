package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.HeroType;

/**
 * One sampled creative direction for a single poster variant — drawn in code (not by the LLM) from
 * the vibe's style-card pools with a seeded {@link java.util.Random}, so the same generation seed
 * reproduces the same run and different generations vary. The three directions of a run carry the
 * hero modes declared by the vibe's {@code variant_plan} (the legacy {@link HeroType#ORDER} only when
 * a vibe declares no plan), and share no composition or accent (sampled without replacement across the run).
 *
 * <p>For {@link HeroType#TYPOGRAPHIC}, {@code heroSubject} is {@code null} — typography is the image,
 * driven only by accent/texture.
 */
public record CreativeDirection(
        HeroType heroType,
        String heroSubject,
        String composition,
        String accent,
        String paletteTwist,
        String typeTreatment
) {}
