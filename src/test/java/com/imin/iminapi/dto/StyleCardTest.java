package com.imin.iminapi.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StyleCardTest {

    @Test
    void legacyTenArgConstructorDefaultsToRequiredPolicyAndPeopleObjectTypographicPlan() {
        StyleCard card = new StyleCard(
                "v", "photo", List.of(),
                List.of("a person"), List.of("a thing"),
                List.of("comp"), List.of("acc"), List.of("twist"), List.of("type"), List.of("ex"));

        assertThat(card.humanPolicy()).isEqualTo(HumanPolicy.REQUIRED);
        assertThat(card.humanStyle()).isNull();
        assertThat(card.variantPlan()).extracting(VariantSlot::mode)
                .containsExactly(HeroType.PEOPLE, HeroType.OBJECT, HeroType.TYPOGRAPHIC);
        assertThat(card.heroSubjectsScene()).isEmpty();
        assertThat(card.heroSubjectsAbstract()).isEmpty();
    }

    @Test
    void heroSubjectsForRoutesEveryModeToItsPool() {
        StyleCard card = new StyleCard(
                "v", "photo", List.of(),
                List.of("people-1"), List.of("object-1"), List.of("scene-1"), List.of("abstract-1"),
                List.of("comp"), List.of("acc"), List.of("twist"), List.of("type"), List.of("ex"),
                HumanPolicy.OPTIONAL, HumanStyle.ABSTRACTED,
                List.of(new VariantSlot(HeroType.SCENE), new VariantSlot(HeroType.OBJECT), new VariantSlot(HeroType.PEOPLE)));

        assertThat(card.heroSubjectsFor(HeroType.PEOPLE)).containsExactly("people-1");
        assertThat(card.heroSubjectsFor(HeroType.OBJECT)).containsExactly("object-1");
        assertThat(card.heroSubjectsFor(HeroType.SCENE)).containsExactly("scene-1");
        assertThat(card.heroSubjectsFor(HeroType.ABSTRACT_GRAPHIC)).containsExactly("abstract-1");
        assertThat(card.heroSubjectsFor(HeroType.TYPOGRAPHIC)).isEmpty();
    }

    @Test
    void defaultPlanIsThreeSlotsPeopleObjectTypographic() {
        assertThat(StyleCard.defaultPlan()).extracting(VariantSlot::mode)
                .containsExactly(HeroType.PEOPLE, HeroType.OBJECT, HeroType.TYPOGRAPHIC);
    }
}
