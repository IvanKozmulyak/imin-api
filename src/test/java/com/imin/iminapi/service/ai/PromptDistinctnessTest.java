package com.imin.iminapi.service.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PromptDistinctnessTest {

    // Three realistic, clearly-distinct ~60-word poster prompts (different hero, palette, mood).
    private static final String PROMPT_PEOPLE =
            "Editorial portrait poster of a lone street saxophonist mid-solo under a flickering neon "
            + "marquee, warm amber and deep magenta wash, grain-heavy 35mm film texture, shallow "
            + "depth of field blurring the rain-slicked sidewalk crowd behind her, bold condensed "
            + "headline typography reading the festival name stacked tall along the left margin, "
            + "smoke curling through a single hard backlight, vintage jazz-club intimacy and quiet "
            + "midnight energy throughout the whole composition frame.";

    private static final String PROMPT_OBJECT =
            "Still-life product poster of a single chilled coupe glass holding a luminous citrus "
            + "spritz, cold mint and pale aqua palette against a brushed steel countertop, crisp "
            + "studio rim lighting catching condensation droplets, geometric art-deco border framing "
            + "the upper third, sparkling tonic bubbles frozen in motion, minimalist sans-serif "
            + "labels orbiting the glass, clean refreshing summer-rooftop mood with bright airy "
            + "negative space surrounding the hero object completely throughout.";

    private static final String PROMPT_TYPOGRAPHIC =
            "Type-as-image poster where the event title becomes the entire artwork, towering "
            + "brutalist letterforms cut from cracked concrete and overgrown with creeping ivy, "
            + "muted olive and oxidized-copper palette, harsh raking sunlight casting long sculptural "
            + "shadows across the glyphs, no human figures and no product, distressed risograph "
            + "overprint texture, raw underground warehouse-rave attitude, dense industrial layout "
            + "filling every corner of the tall vertical canvas frame.";

    @Test
    void identicalLongStrings_areFullySimilar() {
        String s = "the quick brown fox jumps over the lazy sleeping dog near the cold river bank";
        assertThat(PromptDistinctness.trigramJaccard(s, s)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void completelyDifferentSentences_areLowSimilarity() {
        String a = "a lone saxophonist plays under warm neon light in the rain at midnight";
        String b = "geometric mint citrus spritz glass sparkles on brushed steel in bright studio";
        assertThat(PromptDistinctness.trigramJaccard(a, b)).isLessThan(0.45);
    }

    @Test
    void textShorterThanThreeWords_hasEmptyTrigramSet_soSimilarityIsZero() {
        // Fewer than 3 words → empty trigram set → 0.0 even against an identical short string.
        assertThat(PromptDistinctness.trigramJaccard("hello there", "hello there"))
                .isEqualTo(0.0);
        assertThat(PromptDistinctness.trigramJaccard("a b", "a b c d e f"))
                .isEqualTo(0.0);
    }

    @Test
    void bothEmpty_returnsZero() {
        assertThat(PromptDistinctness.trigramJaccard("", "")).isEqualTo(0.0);
    }

    @Test
    void maxPairwiseSimilarity_picksTheMostSimilarPair() {
        String base = "midnight jazz saxophone poster with warm neon amber glow and bold headline";
        String nearDuplicate =
                "midnight jazz saxophone poster with warm neon amber glow and big headline";
        String different =
                "bright mint citrus spritz glass on brushed steel with crisp studio rim light";

        double max = PromptDistinctness.maxPairwiseSimilarity(
                List.of(base, nearDuplicate, different));
        double mostSimilarPair = PromptDistinctness.trigramJaccard(base, nearDuplicate);

        assertThat(max).isCloseTo(mostSimilarPair, within(1e-9));
        // The near-duplicate pair dominates the unrelated pair.
        assertThat(max).isGreaterThan(PromptDistinctness.trigramJaccard(base, different));
        assertThat(max).isGreaterThan(0.45);
    }

    @Test
    void maxPairwiseSimilarity_withFewerThanTwoPrompts_isZero() {
        assertThat(PromptDistinctness.maxPairwiseSimilarity(List.of())).isEqualTo(0.0);
        assertThat(PromptDistinctness.maxPairwiseSimilarity(List.of("only one prompt here")))
                .isEqualTo(0.0);
    }

    @Test
    void allPairwiseBelow_isTrueForThreeClearlyDifferentPosterPrompts() {
        assertThat(PromptDistinctness.allPairwiseBelow(
                List.of(PROMPT_PEOPLE, PROMPT_OBJECT, PROMPT_TYPOGRAPHIC), 0.45))
                .isTrue();
    }

    @Test
    void allPairwiseBelow_isFalseWhenTwoPromptsAreNearDuplicates() {
        String a =
                "midnight jazz saxophone poster with warm neon amber glow and bold condensed headline";
        String nearDuplicateOfA =
                "midnight jazz saxophone poster with warm neon amber glow and bold condensed title";
        assertThat(PromptDistinctness.allPairwiseBelow(
                List.of(a, nearDuplicateOfA, PROMPT_OBJECT), 0.45))
                .isFalse();
    }

    @Test
    void allPairwiseBelow_withFewerThanTwoPrompts_isVacuouslyTrue() {
        assertThat(PromptDistinctness.allPairwiseBelow(List.of(), 0.45)).isTrue();
        assertThat(PromptDistinctness.allPairwiseBelow(List.of("single"), 0.45)).isTrue();
    }
}
