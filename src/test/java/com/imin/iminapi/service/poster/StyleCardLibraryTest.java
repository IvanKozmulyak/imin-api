package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.Rgb;
import com.imin.iminapi.dto.StyleCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the {@code test_vibe.yaml} fixture (on the test classpath under
 * {@code src/test/resources/vibes/style-cards/}) via a real {@link DefaultResourceLoader} and the
 * production glob, verifying the YAML → {@link StyleCard} mapping end to end.
 */
class StyleCardLibraryTest {

    private StyleCardLibrary library;

    @BeforeEach
    void setUp() {
        library = new StyleCardLibrary(
                new DefaultResourceLoader(),
                "classpath*:vibes/style-cards/*.yaml");
        library.load();
    }

    @Test
    void loadsFixtureCardKeyedByFilenameStem() {
        assertThat(library.size()).isGreaterThanOrEqualTo(1);
        assertThat(library.has("test_vibe")).isTrue();
        assertThat(library.vibeIds()).contains("test_vibe");

        Optional<StyleCard> card = library.get("test_vibe");
        assertThat(card).isPresent();
        // vibeId comes from the filename stem, never the body.
        assertThat(card.get().vibeId()).isEqualTo("test_vibe");
        assertThat(card.get().medium()).isEqualTo("photo");
    }

    @Test
    void parsesPaletteToRgbTriples() {
        StyleCard card = library.get("test_vibe").orElseThrow();
        assertThat(card.palette()).containsExactly(
                new Rgb(12, 12, 12),
                new Rgb(233, 64, 87),
                new Rgb(240, 236, 225));
    }

    @Test
    void populatesHeroSubjectPools() {
        StyleCard card = library.get("test_vibe").orElseThrow();

        assertThat(card.heroSubjectsPeople()).isNotEmpty();
        assertThat(card.heroSubjectsObject()).isNotEmpty();

        assertThat(card.heroSubjectsFor(HeroType.PEOPLE))
                .isEqualTo(card.heroSubjectsPeople())
                .anyMatch(s -> s.contains("vocalist"));
        assertThat(card.heroSubjectsFor(HeroType.OBJECT))
                .isEqualTo(card.heroSubjectsObject())
                .anyMatch(s -> s.contains("cassette"));
        // TYPOGRAPHIC draws no hero subject — type is the image.
        assertThat(card.heroSubjectsFor(HeroType.TYPOGRAPHIC)).isEmpty();
    }

    @Test
    void parsesRemainingListPools() {
        StyleCard card = library.get("test_vibe").orElseThrow();
        assertThat(card.compositions()).isNotEmpty();
        assertThat(card.accents()).isNotEmpty();
        assertThat(card.paletteTwists()).isNotEmpty();
        assertThat(card.typeTreatments()).isNotEmpty();
        assertThat(card.examplePrompts()).isNotEmpty();
    }

    @Test
    void unknownVibeIsAbsentAndNeverThrows() {
        assertThat(library.has("does_not_exist")).isFalse();
        assertThat(library.get("does_not_exist")).isEmpty();
        assertThat(library.get(null)).isEmpty();
        assertThat(library.has(null)).isFalse();
    }
}
