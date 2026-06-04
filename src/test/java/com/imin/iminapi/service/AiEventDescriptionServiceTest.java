package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.UniversalRules;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.service.poster.PosterTextSpecFactory;
import com.imin.iminapi.service.poster.VibeLibrary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AiEventDescriptionServiceTest {

    private final VibeLibrary vibeLibrary = new FakeVibeLibrary();

    private AiEventDescriptionService service;

    private static final Vibe BRUTALIST = new Vibe(
            "brutalist_techno", "Brutalist Techno", List.of("techno"),
            "raw exposed concrete, harsh single-source lighting", List.of("#0A0A0A", "#FF2D00"),
            "oversized condensed grotesk, all caps", "giant headline, lots of negative space",
            List.of("severe"), List.of("color gradients", "warmth"),
            "recraft", List.of("reference-images/Brutalist Techno"), null, "brutalist", false);

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new AiEventDescriptionService(null, vibeLibrary, new PosterTextSpecFactory());
    }

    private EventCreatorRequest req(String pinnedVibeId) {
        return new EventCreatorRequest(
                "vibe", "tone", "techno", "city",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, null, null, null, null,
                pinnedVibeId, null);
    }

    @Test
    void buildPrompt_pinnedVibe_injectsStructuredPresetAndUniversalRules() {
        EventCreatorRequest request = new EventCreatorRequest(
                "warehouse rave", "energetic", "techno", "Berlin",
                LocalDate.of(2026, 6, 7), List.of("INSTAGRAM"),
                "DJ A, DJ B", "RSO", "BIG NIGHT - BERLIN", null,
                "Schnellerstrasse 137", "https://imin.wtf/e/big-night",
                "brutalist_techno", null);

        String prompt = service.buildPrompt(request, null);

        assertThat(prompt).contains("must be exactly \"brutalist_techno\"");
        assertThat(prompt).contains("Visual style: raw exposed concrete");
        assertThat(prompt).doesNotContain("Palette:");
        assertThat(prompt).doesNotContain("#0A0A0A");
        assertThat(prompt).contains("Typography: oversized condensed grotesk");
        assertThat(prompt).contains("Avoid: color gradients, warmth");
        assertThat(prompt).contains("AVOID in the artwork: blurry, watermark");
        assertThat(prompt).contains("stock flyer layout");
        assertThat(prompt).contains("template poster");
        assertThat(prompt).contains("Create a finished event poster");
        assertThat(prompt).contains("Render the following required text exactly as written");
        assertThat(prompt).contains("\"BIG NIGHT - BERLIN\"");
        assertThat(prompt).contains("\"7 JUN 2026\"");
        assertThat(prompt).doesNotContain("EMPTY typographic surfaces");
        assertThat(prompt).doesNotContain("No letters, no glyphs");
        assertThat(prompt).doesNotContain("Decorative abstract lettering is allowed");
        assertThat(prompt).contains("IP rule: never in the style of a real artist");
        assertThat(prompt).doesNotContain("(no descriptor available)");
    }

    @Test
    void buildPrompt_noVibeId_autoSuggestsFromGenre() {
        String prompt = service.buildPrompt(req(null), null);

        assertThat(prompt).contains("must be exactly \"brutalist_techno\"");
        assertThat(prompt).contains("Visual style: raw exposed concrete");
    }

    @Test
    void buildPrompt_forcesNineBySixteenAspectRatio() {
        String prompt = service.buildPrompt(req("brutalist_techno"), null);

        assertThat(prompt).contains("aspect_ratio: always exactly \"9:16\"");
        assertThat(prompt).doesNotContain("one of 4:5, 1:1, 9:16, 16:9");
    }

    @Test
    void forcePortrait_setsEveryVariantToNineBySixteen() {
        String body = "word ".repeat(50).trim();
        List<PosterVariant> variants = List.of(
                new PosterVariant("atmospheric", body, "4:5", "Design"),
                new PosterVariant("graphic", body, "1:1", "Design"),
                new PosterVariant("minimal", body, "16:9", "Design"));

        PosterConcept forced = service.forcePortrait(
                new PosterConcept("brutalist_techno", "palette", variants));

        assertThat(forced.variants()).extracting(PosterVariant::aspectRatio)
                .containsExactly("9:16", "9:16", "9:16");
        // Everything except the aspect ratio is preserved.
        assertThat(forced.variants()).extracting(PosterVariant::variantStyle)
                .containsExactly("atmospheric", "graphic", "minimal");
        assertThat(forced.variants()).extracting(PosterVariant::ideogramPrompt)
                .containsExactly(body, body, body);
        assertThat(forced.variants()).extracting(PosterVariant::styleType)
                .containsExactly("Design", "Design", "Design");
        assertThat(forced.subStyleTag()).isEqualTo("brutalist_techno");
        assertThat(forced.colorPaletteDescription()).isEqualTo("palette");
    }

    @Test
    void validate_acceptsKnownVibe_rejectsUnknown() {
        String body = "word ".repeat(50).trim();
        List<PosterVariant> variants = List.of(
                new PosterVariant("atmospheric", body, "4:5", "Design"),
                new PosterVariant("graphic", body, "1:1", "Design"),
                new PosterVariant("minimal", body, "9:16", "Design"));

        assertThat(service.validate(new PosterConcept("brutalist_techno", "palette", variants))).isNull();
        assertThat(service.validate(new PosterConcept("bogus", "palette", variants)))
                .contains("known vibe id");
    }

    @Test
    void validate_withRequest_rejectsAnyVariantPromptMissingRequiredText() {
        EventCreatorRequest request = new EventCreatorRequest(
                "warehouse rave", "energetic", "techno", "Berlin",
                LocalDate.of(2026, 6, 7), List.of("INSTAGRAM"),
                "DJ A, DJ B", "RSO", "BIG NIGHT - BERLIN", null,
                "Schnellerstrasse 137", "https://imin.wtf/e/big-night",
                "brutalist_techno", null);
        String completePrompt = "Create a finished brutalist event poster for \"BIG NIGHT - BERLIN\" on "
                + "\"7 JUN 2026\" at \"RSO\" with native typography, raw exposed concrete, harsh light, "
                + "oversized condensed grotesk type, torn label details, stamped rails, sparse composition, "
                + "severe contrast, industrial texture, precise hierarchy, and a clean Recraft-ready layout.";
        String promptMissingDate = "Create a finished brutalist event poster for \"BIG NIGHT - BERLIN\" at \"RSO\" with native typography, "
                + "raw exposed concrete, harsh light, oversized condensed grotesk type, torn label details, "
                + "stamped rails, sparse composition, severe contrast, industrial texture, precise hierarchy, "
                + "and a clean Recraft-ready layout, but omit the exact date string from the generated poster prompt.";
        List<PosterVariant> variants = List.of(
                new PosterVariant("atmospheric", completePrompt, "4:5", "Design"),
                new PosterVariant("graphic", promptMissingDate, "1:1", "Design"),
                new PosterVariant("minimal", completePrompt, "9:16", "Design"));

        String error = service.validate(new PosterConcept("brutalist_techno", "palette", variants), request);

        assertThat(error).contains("required text");
        assertThat(error).contains("variant[1]");
        assertThat(error).contains("7 JUN 2026");
    }

    private static class FakeVibeLibrary extends VibeLibrary {
        FakeVibeLibrary() {
            super(null, "");
        }

        @Override
        public Optional<Vibe> byId(String id) {
            return "brutalist_techno".equals(id) ? Optional.of(BRUTALIST) : Optional.empty();
        }

        @Override
        public boolean hasVibe(String id) {
            return "brutalist_techno".equals(id);
        }

        @Override
        public UniversalRules universalRules() {
            return new UniversalRules(List.of("4:5"), "blurry, watermark", "never in the style of a real artist");
        }

        @Override
        public Vibe suggestForGenre(String genre) {
            return BRUTALIST;
        }
    }
}
