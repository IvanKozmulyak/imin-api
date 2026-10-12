package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.Vibe;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;

import static com.imin.iminapi.service.AiEventDescriptionService.posterTextIn;
import static com.imin.iminapi.service.AiEventDescriptionService.visualHint;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every style-card pool entry and vibe field the art director sees must describe visuals only:
 * a quoted kicker or a "footer of tiny text" in a hint is copied into the prompt and printed as
 * invented words on the organizer's poster.
 */
class StyleCardVisualOnlyTest {

    @Test
    void everyHintTheArtDirectorSeesIsVisualOnly() {
        StyleCardLibrary cards = new StyleCardLibrary(new DefaultResourceLoader(), "file:src/main/resources/vibes/style-cards/*.yaml");
        cards.load();
        VibeLibrary vibes = new VibeLibrary(new DefaultResourceLoader(), "file:src/main/resources/vibes.yaml");
        vibes.load();
        assertThat(cards.size()).isPositive();
        assertThat(vibes.size()).isPositive();

        List<String> offenders = new ArrayList<>();
        for (String id : cards.vibeIds()) {
            StyleCard c = cards.get(id).orElseThrow();
            check(offenders, id + ".hero_subjects.people", c.heroSubjectsPeople());
            check(offenders, id + ".hero_subjects.object", c.heroSubjectsObject());
            check(offenders, id + ".hero_subjects.scene", c.heroSubjectsScene());
            check(offenders, id + ".hero_subjects.abstract", c.heroSubjectsAbstract());
            check(offenders, id + ".compositions", c.compositions());
            check(offenders, id + ".accents", c.accents());
            check(offenders, id + ".palette_twists", c.paletteTwists());
            check(offenders, id + ".type_treatments", c.typeTreatments());
        }
        for (Vibe v : vibes.all()) {
            check(offenders, v.id() + ".visual_style", List.of(nz(v.visualStyle())));
            check(offenders, v.id() + ".typography", List.of(nz(v.typography())));
            check(offenders, v.id() + ".composition", List.of(nz(v.composition())));
            check(offenders, v.id() + ".subject", List.of(nz(v.subject())));
        }
        assertThat(offenders).isEmpty();
    }

    private static void check(List<String> offenders, String where, List<String> entries) {
        if (entries == null) return;
        for (String e : entries) {
            String hit = posterTextIn(visualHint(e), List.of());
            if (hit != null) offenders.add(where + " [" + hit + "]: " + e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
