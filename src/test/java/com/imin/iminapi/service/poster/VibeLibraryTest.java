package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.Vibe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain unit tests for the Vibe Library config loader — no Spring context, no network. */
class VibeLibraryTest {

    private VibeLibrary library;

    @BeforeEach
    void setUp() {
        library = new VibeLibrary(new DefaultResourceLoader(), "classpath:vibes.yaml");
        library.load();
    }

    @Test
    void loads_twelve_vibes() {
        assertThat(library.size()).isEqualTo(12);
        assertThat(library.all()).extracting(Vibe::id)
                .containsExactly(
                        "brutalist_techno", "berlin_minimal", "acid_rave_y2k", "psytrance_goa",
                        "industrial_hard_groove", "liquid_melodic", "hyperpop_club", "dnb_jungle",
                        "afro_amapiano", "open_air_festival", "disco_italo", "dark_experimental");
    }

    @Test
    void vibe_carries_structured_preset() {
        Vibe v = library.byId("brutalist_techno").orElseThrow();
        assertThat(v.name()).isEqualTo("Brutalist Techno");
        assertThat(v.palette()).contains("#FF2D00");
        assertThat(v.typography()).contains("grotesk");
        assertThat(v.avoid()).contains("warmth");
        assertThat(v.layoutTemplate()).isEqualTo("brutalist");
    }

    @Test
    void genre_auto_suggest_maps_to_vibe() {
        assertThat(library.suggestForGenre("techno").id()).isEqualTo("brutalist_techno");
        assertThat(library.suggestForGenre("PSYTRANCE").id()).isEqualTo("psytrance_goa");
        assertThat(library.suggestForGenre("amapiano").id()).isEqualTo("afro_amapiano");
    }

    @Test
    void unknown_genre_falls_back_to_liquid_melodic() {
        assertThat(library.suggestForGenre("polka").id()).isEqualTo("liquid_melodic");
        assertThat(library.suggestForGenre(null).id()).isEqualTo("liquid_melodic");
    }

    @Test
    void universal_rules_present() {
        assertThat(library.universalRules().aspectRatios())
                .containsExactly("4:5", "1:1", "9:16", "16:9");
        assertThat(library.universalRules().negativePrompt()).contains("gibberish text");
        assertThat(library.universalRules().ipRule()).contains("in the style of");
    }

    @Test
    void six_vibes_have_curated_references_six_are_text_only() {
        long withRefs = library.all().stream().filter(v -> !v.references().isEmpty()).count();
        long textOnly = library.all().stream().filter(Vibe::textOnly).count();
        assertThat(withRefs).isEqualTo(6);
        assertThat(textOnly).isEqualTo(6);
        // The curated vibes point at their reference folders.
        assertThat(library.byId("brutalist_techno").orElseThrow().references())
                .containsExactly("reference-images/Brutalist Techno");
        assertThat(library.byId("disco_italo").orElseThrow().references())
                .containsExactly("reference-images/Disco - Italo Revival");
        assertThat(library.byId("disco_italo").orElseThrow().textOnly()).isFalse();
    }
}
