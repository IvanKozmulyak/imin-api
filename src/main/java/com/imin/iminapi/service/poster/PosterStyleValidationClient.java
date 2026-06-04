package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.StyleCard;

import java.util.List;

/**
 * Vision client for the style-adherence gate: given a rendered poster PNG, the vibe's style card,
 * and the variant's declared hero type, decide whether the poster actually matches the intended
 * aesthetic (hero present, medium right, palette right). Mirrors {@link PosterTextValidationClient}
 * (temp 0, response_format=json_object, image attached).
 */
public interface PosterStyleValidationClient {

    StyleValidationResult validate(byte[] imageBytes, StyleCard card, HeroType declaredHeroType);

    /**
     * @param accepted           overall pass/fail
     * @param heroSubjectPresent the declared hero is actually present (e.g. a people-led variant shows a person)
     * @param mediumMatches      the rendered medium matches the card (photo vs illustration vs collage)
     * @param paletteMatches     the dominant colors are broadly consistent with the card palette
     * @param reasons            short human-readable reasons (especially on reject)
     */
    record StyleValidationResult(
            boolean accepted,
            boolean heroSubjectPresent,
            boolean mediumMatches,
            boolean paletteMatches,
            List<String> reasons
    ) {}
}
