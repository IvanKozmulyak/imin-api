package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.Rgb;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.service.ai.CreativeDirectionSampler.SampledRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreativeDirectionSamplerTest {

    private final CreativeDirectionSampler sampler = new CreativeDirectionSampler();

    private static final List<String> PEOPLE = List.of(
            "a lone dancer mid-spin under a single spotlight",
            "two friends laughing on a fire escape at dusk",
            "a chef plating a dish in a steamy kitchen",
            "a skater carving an empty pool at golden hour");

    private static final List<String> OBJECTS = List.of(
            "a cracked vinyl record on a concrete floor",
            "a neon cocktail glass sweating with condensation",
            "a vintage boombox half-buried in sand",
            "a single rose pressed under shattered glass");

    private StyleCard fixture() {
        return new StyleCard(
                "neon-noir",
                "photo",
                List.of(new Rgb(10, 10, 12), new Rgb(220, 30, 90), new Rgb(40, 200, 220)),
                PEOPLE,
                OBJECTS,
                List.of(
                        "hero bottom-left, type stacked top-right",
                        "centered hero, type wrapping the frame edge",
                        "extreme close crop, tiny type baseline",
                        "hero in negative space, type as a vertical spine",
                        "split frame, hero one half type the other",
                        "low-angle hero, oversized type behind"),
                List.of(
                        "harsh on-camera flash",
                        "heavy film grain",
                        "photocopy degradation",
                        "chromatic aberration fringing",
                        "halftone dot screen"),
                List.of(
                        "condensed all-caps grotesk",
                        "warped liquid display serif",
                        "stencil-cut industrial sans"),
                List.of(
                        "letters bleeding off every edge",
                        "type kerned to overlap and collide",
                        "single giant glyph as the hero"),
                List.of(
                        "Poster for a midnight warehouse rave, neon-noir, harsh flash, \"AFTER DARK\".",
                        "Poster for an underground jazz night, grainy film, \"BLUE ROOM\".",
                        "Poster for a rooftop electro set, chromatic fringe, \"ALTITUDE\"."));
    }

    @Test
    void returnsExactlyThreeDirectionsWithHeroTypesInOrder() {
        SampledRun run = sampler.sample(fixture(), 42L);

        assertThat(run.directions()).hasSize(3);
        assertThat(run.directions().get(0).heroType()).isEqualTo(HeroType.PEOPLE);
        assertThat(run.directions().get(1).heroType()).isEqualTo(HeroType.OBJECT);
        assertThat(run.directions().get(2).heroType()).isEqualTo(HeroType.TYPOGRAPHIC);
    }

    @Test
    void sameSeedProducesIdenticalResultDeeply() {
        StyleCard card = fixture();
        SampledRun a = sampler.sample(card, 1234L);
        SampledRun b = sampler.sample(card, 1234L);

        // Records are value types — deep equality covers every field of every direction + the prompt.
        assertThat(a).isEqualTo(b);
        assertThat(a.examplePrompt()).isEqualTo(b.examplePrompt());
        for (int i = 0; i < 3; i++) {
            CreativeDirection da = a.directions().get(i);
            CreativeDirection db = b.directions().get(i);
            assertThat(da.heroType()).isEqualTo(db.heroType());
            assertThat(da.heroSubject()).isEqualTo(db.heroSubject());
            assertThat(da.composition()).isEqualTo(db.composition());
            assertThat(da.accent()).isEqualTo(db.accent());
            assertThat(da.paletteTwist()).isEqualTo(db.paletteTwist());
            assertThat(da.typeTreatment()).isEqualTo(db.typeTreatment());
        }
    }

    @Test
    void differentSeedsVaryAtLeastOneField() {
        StyleCard card = fixture();
        SampledRun base = sampler.sample(card, 1L);

        boolean foundDifference = false;
        for (long seed : new long[]{2L, 3L, 4L, 5L, 99L, 7777L}) {
            SampledRun other = sampler.sample(card, seed);
            if (!other.equals(base)) {
                foundDifference = true;
                break;
            }
        }
        assertThat(foundDifference)
                .as("at least one of several other seeds should differ from the base run")
                .isTrue();
    }

    @Test
    void compositionsAndAccentsAreDistinctAcrossTheThreeDirections() {
        SampledRun run = sampler.sample(fixture(), 314159L);

        List<String> compositions = run.directions().stream().map(CreativeDirection::composition).toList();
        List<String> accents = run.directions().stream().map(CreativeDirection::accent).toList();

        assertThat(compositions).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(accents).doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    void heroSubjectsComeFromTheRightPoolAndTypographicIsNull() {
        StyleCard card = fixture();
        // Check across several seeds so we never rely on one lucky draw.
        for (long seed : new long[]{0L, 1L, 17L, 256L, 9001L}) {
            SampledRun run = sampler.sample(card, seed);

            CreativeDirection people = run.directions().get(0);
            CreativeDirection object = run.directions().get(1);
            CreativeDirection typographic = run.directions().get(2);

            assertThat(people.heroSubject()).isNotNull();
            assertThat(PEOPLE).contains(people.heroSubject());

            assertThat(object.heroSubject()).isNotNull();
            assertThat(OBJECTS).contains(object.heroSubject());

            assertThat(typographic.heroSubject()).isNull();
        }
    }

    @Test
    void examplePromptIsOneOfTheCardsExamplePrompts() {
        StyleCard card = fixture();
        for (long seed : new long[]{0L, 1L, 2L, 3L, 42L, 1000L}) {
            SampledRun run = sampler.sample(card, seed);
            assertThat(run.examplePrompt()).isNotNull();
            assertThat(card.examplePrompts()).contains(run.examplePrompt());
        }
    }

    @Test
    void nullCardYieldsThreeDirectionsWithNullFieldsAndNullPrompt() {
        SampledRun run = sampler.sample(null, 7L);

        assertThat(run.directions()).hasSize(3);
        assertThat(run.examplePrompt()).isNull();
        assertThat(run.directions().get(0).heroType()).isEqualTo(HeroType.PEOPLE);
        assertThat(run.directions().get(1).heroType()).isEqualTo(HeroType.OBJECT);
        assertThat(run.directions().get(2).heroType()).isEqualTo(HeroType.TYPOGRAPHIC);
        for (CreativeDirection d : run.directions()) {
            assertThat(d.heroSubject()).isNull();
            assertThat(d.composition()).isNull();
            assertThat(d.accent()).isNull();
            assertThat(d.paletteTwist()).isNull();
            assertThat(d.typeTreatment()).isNull();
        }
    }

    @Test
    void emptyPoolsYieldNullFieldsWithoutThrowing() {
        StyleCard empty = new StyleCard(
                "blank", "photo", List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        SampledRun run = sampler.sample(empty, 5L);

        assertThat(run.directions()).hasSize(3);
        assertThat(run.examplePrompt()).isNull();
        for (CreativeDirection d : run.directions()) {
            assertThat(d.heroSubject()).isNull();
            assertThat(d.composition()).isNull();
            assertThat(d.accent()).isNull();
            assertThat(d.paletteTwist()).isNull();
            assertThat(d.typeTreatment()).isNull();
        }
    }

    @Test
    void smallPoolFallsBackToIndependentDrawsAllowingRepeats() {
        // paletteTwists has only 1 entry (< 3) so all three directions must reuse it without throwing.
        StyleCard card = new StyleCard(
                "tiny", "photo", List.of(),
                PEOPLE, OBJECTS,
                List.of("a", "b", "c", "d"),
                List.of("flash", "grain", "halftone"),
                List.of("only-twist"),
                List.of("t1", "t2", "t3"),
                List.of("prompt"));

        SampledRun run = sampler.sample(card, 11L);

        assertThat(run.directions()).hasSize(3);
        assertThat(run.directions()).allSatisfy(d ->
                assertThat(d.paletteTwist()).isEqualTo("only-twist"));
    }

    private StyleCard plannedCard(java.util.List<com.imin.iminapi.dto.VariantSlot> plan,
                                  java.util.List<String> scene, java.util.List<String> abstractPool) {
        return new StyleCard(
                "planned", "photo",
                List.of(new Rgb(10, 10, 12)),
                PEOPLE, OBJECTS, scene, abstractPool,
                List.of("c1", "c2", "c3", "c4"),
                List.of("a1", "a2", "a3", "a4"),
                List.of("t1", "t2", "t3", "t4"),
                List.of("ty1", "ty2", "ty3", "ty4"),
                List.of("ex1"),
                com.imin.iminapi.dto.HumanPolicy.OPTIONAL, null, plan);
    }

    @Test
    void heroTypesFollowTheDeclaredPlanNotTheLegacyOrder() {
        var plan = List.of(
                new com.imin.iminapi.dto.VariantSlot(HeroType.SCENE),
                new com.imin.iminapi.dto.VariantSlot(HeroType.OBJECT),
                new com.imin.iminapi.dto.VariantSlot(HeroType.TYPOGRAPHIC));
        SampledRun run = sampler.sample(
                plannedCard(plan, List.of("a wide festival field at dusk"), List.of()), 42L);

        assertThat(run.directions()).extracting(CreativeDirection::heroType)
                .containsExactly(HeroType.SCENE, HeroType.OBJECT, HeroType.TYPOGRAPHIC);
        assertThat(run.directions().get(0).heroSubject()).isEqualTo("a wide festival field at dusk");
        assertThat(run.directions().get(2).heroSubject()).isNull(); // typographic draws no subject
    }

    @Test
    void repeatedModeDrawsDistinctSubjectsWithoutReplacement() {
        var plan = List.of(
                new com.imin.iminapi.dto.VariantSlot(HeroType.PEOPLE),
                new com.imin.iminapi.dto.VariantSlot(HeroType.PEOPLE),
                new com.imin.iminapi.dto.VariantSlot(HeroType.PEOPLE));
        SampledRun run = sampler.sample(plannedCard(plan, List.of(), List.of()), 7L);

        List<String> subjects = run.directions().stream().map(CreativeDirection::heroSubject).toList();
        assertThat(subjects).doesNotContainNull().doesNotHaveDuplicates(); // PEOPLE pool has 4 >= 3
        assertThat(PEOPLE).containsAll(subjects);
    }
}
