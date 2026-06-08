package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.HumanPolicy;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.VariantSlot;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the authored production style-cards: every variant_plan is well-formed and consistent. */
class VibePlanConsistencyTest {

    private static final List<String> PRODUCTION_VIBES = List.of(
            "afro_amapiano", "dark_experimental", "hyperpop_club", "disco_italo",
            "dnb_jungle", "industrial_hard_groove", "berlin_minimal",
            "liquid_melodic", "acid_rave_y2k", "brutalist_techno",
            "open_air_festival", "psytrance_goa");

    private final StyleCardLibrary library = load();

    private static StyleCardLibrary load() {
        StyleCardLibrary lib = new StyleCardLibrary(
                new DefaultResourceLoader(), "classpath*:vibes/style-cards/*.yaml");
        lib.load();
        return lib;
    }

    @Test
    void everyProductionVibeHasAThreeSlotPlan() {
        for (String vibe : PRODUCTION_VIBES) {
            StyleCard card = library.get(vibe).orElseThrow(() -> new AssertionError("missing card: " + vibe));
            assertThat(card.variantPlan()).as("%s plan size", vibe).hasSize(3);
        }
    }

    @Test
    void forbiddenVibesHaveNoPeopleSlotAndOptionalRareRequiredAreConsistent() {
        for (String vibe : PRODUCTION_VIBES) {
            StyleCard card = library.get(vibe).orElseThrow();
            boolean hasPeople = card.variantPlan().stream().map(VariantSlot::mode).anyMatch(HeroType.PEOPLE::equals);
            if (card.humanPolicy() == HumanPolicy.FORBIDDEN) {
                assertThat(hasPeople).as("%s is forbidden ⇒ no people slot", vibe).isFalse();
            }
            if (card.humanPolicy() == HumanPolicy.REQUIRED) {
                assertThat(hasPeople).as("%s is required ⇒ has a people slot", vibe).isTrue();
            }
        }
    }

    @Test
    void everyNonTypographicPlannedModeHasEnoughDistinctSubjects() {
        // A pool must hold at least as many subjects as the plan uses that mode, so a repeated mode
        // (e.g. disco_italo people x3) can draw distinct subjects without replacement.
        for (String vibe : PRODUCTION_VIBES) {
            StyleCard card = library.get(vibe).orElseThrow();
            for (HeroType mode : List.of(HeroType.PEOPLE, HeroType.OBJECT, HeroType.SCENE, HeroType.ABSTRACT_GRAPHIC)) {
                long uses = card.variantPlan().stream().map(VariantSlot::mode).filter(mode::equals).count();
                if (uses == 0) continue;
                assertThat(card.heroSubjectsFor(mode).size())
                        .as("%s mode %s pool must have >= %d subjects", vibe, mode, uses)
                        .isGreaterThanOrEqualTo((int) uses);
            }
        }
    }
}
