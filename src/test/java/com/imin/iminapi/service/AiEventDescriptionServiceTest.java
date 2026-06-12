package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.HumanPolicy;
import com.imin.iminapi.dto.HumanStyle;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.Rgb;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.StyleMode;
import com.imin.iminapi.dto.VariantSlot;
import com.imin.iminapi.dto.UniversalRules;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.service.ai.CreativeDirection;
import com.imin.iminapi.service.ai.CreativeDirectionSampler;
import com.imin.iminapi.service.ai.CreativeDirectionSampler.SampledRun;
import com.imin.iminapi.service.poster.PosterTextSpecFactory;
import com.imin.iminapi.service.poster.StyleCardLibrary;
import com.imin.iminapi.service.poster.VibeLibrary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AiEventDescriptionServiceTest {

    private final VibeLibrary vibeLibrary = new FakeVibeLibrary();
    private final StyleCardLibrary styleCardLibrary = new StyleCardLibrary(new org.springframework.core.io.DefaultResourceLoader(), "classpath*:nonexistent/*.yaml");
    private final AiEventDescriptionService service =
            new AiEventDescriptionService(null, vibeLibrary, new PosterTextSpecFactory(),
                    styleCardLibrary, new CreativeDirectionSampler());

    private static final Vibe BRUTALIST = new Vibe(
            "brutalist_techno", "Brutalist Techno", List.of("techno"),
            "raw exposed concrete, harsh single-source lighting", List.of("#0A0A0A", "#FF2D00"),
            "oversized condensed grotesk, all caps", "giant headline, lots of negative space",
            List.of("severe"), List.of("color gradients", "warmth"),
            "recraft", List.of("reference-images/Brutalist Techno"), null, "brutalist", false,
            "a lone figure dwarfed by raw concrete under one hard light", StyleMode.CURATED_SUBSTYLE, "urban_drama");

    private SampledRun sampled() {
        return new SampledRun(List.of(
                new CreativeDirection(HeroType.PEOPLE, "lone dancer mid-motion under one cold spotlight",
                        "giant headline top, hero anchored bottom third", "heavy film grain",
                        "near-black with one acid-green accent", "condensed all-caps, tight tracking"),
                new CreativeDirection(HeroType.OBJECT, "monolithic concrete stairwell shot from below",
                        "full-bleed texture, headline up the left edge", "photocopy scratches and dust",
                        "black with vermilion accent", "outline headline over solid sub-line"),
                new CreativeDirection(HeroType.TYPOGRAPHIC, null,
                        "type fractured across three baselines", "xerox photocopy degradation",
                        "monochrome with one signal accent", "stacked grotesk, strict left alignment")),
                "An example reference poster prompt written for a different event entirely.");
    }

    private EventCreatorRequest req(String pinnedVibeId) {
        return new EventCreatorRequest(
                "vibe", "tone", "techno", "city",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, null, null, null, null,
                pinnedVibeId, null);
    }

    private EventCreatorRequest brief() {
        return new EventCreatorRequest(
                "warehouse rave", "energetic", "techno", "Berlin",
                LocalDate.of(2026, 6, 7), List.of("INSTAGRAM"),
                "DJ A, DJ B", "RSO", "BIG NIGHT - BERLIN", null,
                "Schnellerstrasse 137", "https://imin.wtf/e/big-night",
                "brutalist_techno", null);
    }

    private EventCreatorRequest briefWithBrand() {
        return new EventCreatorRequest(
                "warehouse rave", "energetic", "techno", "Berlin",
                LocalDate.of(2026, 6, 7), List.of("INSTAGRAM"),
                "DJ A, DJ B", "RSO", "BIG NIGHT - BERLIN",
                "#ec4899 (lead); supporting: #f6c04a, #a78bfa",
                "Schnellerstrasse 137", "https://imin.wtf/e/big-night",
                "brutalist_techno", null);
    }

    // ---- brand palette mandate --------------------------------------------------------------------

    @Test
    void buildPrompt_withBrandColours_mandatesBrandPaletteOverVibe() {
        String prompt = service.buildPrompt(briefWithBrand(), BRUTALIST, sampled(), null);

        assertThat(prompt).contains("BRAND PALETTE");
        assertThat(prompt).contains("#ec4899 (lead); supporting: #f6c04a, #a78bfa");
        // brand replaces the sampled palette twists — colour direction comes from the brand alone
        assertThat(prompt).doesNotContain("near-black with one acid-green accent");
        assertThat(prompt).doesNotContain("black with vermilion accent");
        assertThat(prompt).doesNotContain("monochrome with one signal accent");
        // non-colour parts of each direction still apply
        assertThat(prompt).contains("condensed all-caps, tight tracking");
        assertThat(prompt).contains("lone dancer mid-motion under one cold spotlight");
    }

    @Test
    void buildPrompt_withoutBrandColours_keepsVibePaletteAndOmitsBrandBlock() {
        String prompt = service.buildPrompt(brief(), BRUTALIST, sampled(), null);

        assertThat(prompt).doesNotContain("BRAND PALETTE");
        assertThat(prompt).contains("near-black with one acid-green accent");
    }

    // ---- T4: art-director template v2 ------------------------------------------------------------

    @Test
    void buildPrompt_injectsVibeWorldSubjectAndPerVariantDirections() {
        String prompt = service.buildPrompt(brief(), BRUTALIST, sampled(), null);

        assertThat(prompt).contains("ONE-OF-A-KIND nightlife event posters");
        assertThat(prompt).contains("must be exactly \"brutalist_techno\"");
        assertThat(prompt).contains("Visual style: raw exposed concrete");
        assertThat(prompt).contains("Typography: oversized condensed grotesk");
        assertThat(prompt).contains("Hero subject matter for this vibe: a lone figure dwarfed by raw concrete");
        // per-variant sampled directions
        assertThat(prompt).contains("Variant 1 — hero_type \"people\"");
        assertThat(prompt).contains("lone dancer mid-motion under one cold spotlight");
        assertThat(prompt).contains("Variant 2 — hero_type \"object\"");
        assertThat(prompt).contains("monolithic concrete stairwell shot from below");
        assertThat(prompt).contains("Variant 3 — hero_type \"typographic\"");
        assertThat(prompt).contains("typography IS the image");
        // the few-shot anchor + the JSON contract
        assertThat(prompt).contains("An example reference poster prompt written for a different event");
        assertThat(prompt).contains("hero_type: one of people | object | typographic | scene | abstract_graphic");
        assertThat(prompt).contains("Render the following required text exactly as written");
        assertThat(prompt).contains("\"BIG NIGHT - BERLIN\"");
        assertThat(prompt).contains("\"7 JUN 2026\"");
        assertThat(prompt).contains("IP rule: never reference real venues");
        assertThat(prompt).contains("Any people must be rendered photorealistically with correct anatomy");
    }

    @Test
    void buildPrompt_emitsFiveModeContractAndDefaultPhotographicRule() {
        String prompt = service.buildPrompt(brief(), BRUTALIST, sampled(), null);
        assertThat(prompt).contains("hero_type: one of people | object | typographic | scene | abstract_graphic");
        // null style-card ⇒ photographic default ⇒ the photorealistic line is present
        assertThat(prompt).contains("Any people must be rendered photorealistically with correct anatomy");
    }

    // ---- T1: diffusion negative-token leakage is purged ------------------------------------------

    @Test
    void buildPrompt_purgesDiffusionNegativeTokensAndForcedTypePlate() {
        String prompt = service.buildPrompt(brief(), BRUTALIST, sampled(), null);

        assertThat(prompt).doesNotContain("AVOID in the artwork");
        assertThat(prompt).doesNotContain("deformed faces");
        assertThat(prompt).doesNotContain("extra limbs");
        assertThat(prompt).doesNotContain("jpeg artifacts");
        assertThat(prompt).doesNotContain("low-res");
        assertThat(prompt).doesNotContain("blurry");
        assertThat(prompt).doesNotContain("generic neon crowd");
        assertThat(prompt).doesNotContain("typography is the main visual composition");
        assertThat(prompt).doesNotContain("variant_style");
        // the genuinely instructional avoid stays, as an imperative
        assertThat(prompt).contains("No watermarks, logos, signatures, stock-template flyer layouts, or invented words");
    }

    @Test
    void buildPrompt_forcesFourByFiveAndDesign() {
        String prompt = service.buildPrompt(req("brutalist_techno"), BRUTALIST, sampled(), null);
        assertThat(prompt).contains("aspect_ratio: always exactly \"4:5\"");
        assertThat(prompt).contains("style_type: always \"Design\"");
    }

    @Test
    void buildPrompt_appendsReinforcementOnRetry() {
        String prompt = service.buildPrompt(req("brutalist_techno"), BRUTALIST, sampled(),
                "Previous attempt rejected: variants too similar");
        assertThat(prompt).contains("Previous attempt rejected: variants too similar");
        assertThat(prompt).contains("Fix the issue above and return valid JSON only");
    }

    // ---- forcePortrait --------------------------------------------------------------------------

    @Test
    void forcePortrait_setsEveryVariantToFourByFivePreservingHeroType() {
        String body = "word ".repeat(70).trim();
        List<PosterVariant> variants = List.of(
                new PosterVariant("people", body, "9:16", "Design"),
                new PosterVariant("object", body, "1:1", "Design"),
                new PosterVariant("typographic", body, "16:9", "Design"));

        PosterConcept forced = service.forcePortrait(
                new PosterConcept("brutalist_techno", "palette", variants));

        assertThat(forced.variants()).extracting(PosterVariant::aspectRatio)
                .containsExactly("4:5", "4:5", "4:5");
        assertThat(forced.variants()).extracting(PosterVariant::heroType)
                .containsExactly("people", "object", "typographic");
    }

    // ---- T8: concept validation -----------------------------------------------------------------

    private static String peoplePrompt() {
        return "A lone dancer mid-spin under one hard flash, the crowd dissolving into motion blur behind her "
                + "inside a dark concrete room, shot on grainy high-contrast black-and-white film for a tall portrait "
                + "poster. The bold condensed headline stacks heavy across the very top of the frame while the date is "
                + "locked beneath it, and a tracked-out acid-green venue line sits along the bottom edge integrated as "
                + "native poster typography, with crushed shadows swallowing every margin of the brutalist composition.";
    }

    private static String objectPrompt() {
        return "Monolithic concrete stairwell viewed from directly below, raw formwork ribs receding into a black "
                + "void lit by a single burning industrial bulb, heavy photocopy grain and dust scratches degrading "
                + "the whole vertical sheet toward pure xerox texture. Vermilion-orange typography knocks out of the "
                + "deepest shadow: the oversized headline runs ninety degrees up the left edge, the date anchors the "
                + "lower margin, and a small monospaced credit row pins the bottom corner of the stark brutalist sheet.";
    }

    private static String typographicPrompt() {
        return "Oversized condensed grotesk lettering fractures across three separate baselines, knocked clean out of "
                + "a field of crushed film grain and marbled toner streaks, with no imagery whatsoever — the type alone "
                + "is the entire composition, set tight in all-caps with one acid-green accent line slicing diagonally "
                + "through the stack. The dense xerox degradation eats the edges of every glyph while a small monospaced "
                + "credit block anchors the foot of the stark vertical sheet, severe and machine-stamped throughout.";
    }

    private PosterConcept validConcept() {
        return new PosterConcept("brutalist_techno", "near-black with acid green", List.of(
                new PosterVariant("people", peoplePrompt(), "4:5", "Design"),
                new PosterVariant("object", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
    }

    @Test
    void validate_acceptsWellFormedHeroTypedDistinctConcept() {
        assertThat(service.validate(validConcept())).isNull();
    }

    @Test
    void validate_rejectsWrongHeroTypeOrder() {
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("object", peoplePrompt(), "4:5", "Design"),
                new PosterVariant("people", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c)).contains("hero_type must be exactly \"people\"");
    }

    @Test
    void validate_rejectsPeopleVariantWithoutHumanNoun() {
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("people", objectPrompt(), "4:5", "Design"),  // no human noun
                new PosterVariant("object", objectPrompt() + " extra distinct words to differ greatly here today now", "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c)).contains("must contain a human subject");
    }

    @Test
    void validate_rejectsTypographicVariantContainingHuman() {
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("people", peoplePrompt(), "4:5", "Design"),
                new PosterVariant("object", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt() + " a lone dancer appears", "4:5", "Design")));
        assertThat(service.validate(c)).contains("typographic) must not be built around a person");
    }

    // The art-director template itself asks for a "4:5 portrait poster"; a typographic prompt that
    // faithfully names that orientation must NOT be mistaken for a human subject (regression).
    private static String typographicWithPortraitOrientation() {
        return "Oversized condensed grotesk lettering fractures across three separate baselines, knocked clean out "
                + "of crushed film grain and marbled toner streaks and laid out as a 4:5 portrait poster, with no "
                + "imagery whatsoever — the type alone is the entire composition, set tight in all-caps with one "
                + "acid-green accent line slicing diagonally through the stack while dense xerox degradation eats the "
                + "edges of every glyph down to a small monospaced credit block anchoring the foot of the machine-stamped sheet.";
    }

    @Test
    void mentionsHumanSubject_ignoresOrientationAndTypographyVocabulary() {
        assertThat(AiEventDescriptionService.mentionsHumanSubject("a 4:5 portrait poster of pure type")).isFalse();
        assertThat(AiEventDescriptionService.mentionsHumanSubject("portrait orientation, body text, geometric figures")).isFalse();
        assertThat(AiEventDescriptionService.mentionsHumanSubject("a close-crop portrait of a raver")).isTrue();
        assertThat(AiEventDescriptionService.mentionsHumanSubject("a lone dancer in motion")).isTrue();
    }

    @Test
    void validate_acceptsTypographicPromptNamingPortraitOrientation() {
        PosterConcept c = new PosterConcept("brutalist_techno", "pal", List.of(
                new PosterVariant("people", peoplePrompt(), "4:5", "Design"),
                new PosterVariant("object", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicWithPortraitOrientation(), "4:5", "Design")));
        assertThat(service.validate(c)).isNull();
    }

    @Test
    void validate_rejectsPeopleVariantWhoseOnlyHumanWordIsOrientation() {
        String peopleNoActualHuman = "An empty concrete warehouse interior shot wide and low for a 4:5 portrait poster, "
                + "raw formwork ribs and a single hard overhead bulb burning one hot highlight, heavy film grain across the "
                + "cavernous vacant space with nobody in the frame at all. The bold condensed headline stacks across the very "
                + "top while the date locks beneath it and a tracked-out venue line runs along the bottom edge as native "
                + "typography filling the tall vertical sheet completely from corner to corner throughout the whole layout.";
        PosterConcept c = new PosterConcept("brutalist_techno", "pal", List.of(
                new PosterVariant("people", peopleNoActualHuman, "4:5", "Design"),
                new PosterVariant("object", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c)).contains("must contain a human subject");
    }

    @Test
    void validate_rejectsNearDuplicatePrompts() {
        String p = peoplePrompt();
        PosterConcept c = new PosterConcept("brutalist_techno", "pal", List.of(
                new PosterVariant("people", p, "4:5", "Design"),
                new PosterVariant("object", p.replace("dancer", "stairwell figure"), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c)).contains("variants too similar");
    }

    @Test
    void validate_acceptsPeopleVariantDescribedAsWomanRaverOrDj() {
        // Real "people" heroes (and the style-card pools) use words like woman/man/raver/DJ/club-goer
        // that the narrow base noun list missed. None of crowd/dancer/face/body/portrait appear here.
        String people = "A young woman in mirror-lens sunglasses leans toward the camera holding a tall "
                + "champagne flute under a hard on-camera flash, a lone raver beside her and a DJ hunched over "
                + "the decks in deep club shadow, shot on grainy high-contrast film for a tall vertical poster. "
                + "The bold condensed headline stacks heavy across the top while the date locks beneath it and a "
                + "tracked-out acid-green venue line runs along the bottom edge as native typography filling the frame.";
        PosterConcept c = new PosterConcept("brutalist_techno", "pal", List.of(
                new PosterVariant("people", people, "4:5", "Design"),
                new PosterVariant("object", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c)).isNull();
    }

    @Test
    void validate_withRequest_matchesRequiredTextCaseInsensitively() {
        EventCreatorRequest request = brief(); // required: BIG NIGHT - BERLIN, 7 JUN 2026, RSO
        String people = "A young woman dances in motion blur under a hard flash in a dark concrete room while a "
                + "DJ works the decks behind her, shot on grainy film. The headline \"Big Night - Berlin\" stacks "
                + "across the top, the date \"7 jun 2026\" locks beneath it and the venue \"rso\" sits along the bottom "
                + "edge as native acid-green typography filling the tall vertical frame from corner to corner completely.";
        String object = "Monolithic concrete stairwell shot from directly below under one industrial bulb, heavy "
                + "photocopy grain across the vertical sheet. Vermilion typography knocks out of shadow: the headline "
                + "\"big night - berlin\" runs up the left edge, the date \"7 JUN 2026\" sits in the lower margin and "
                + "\"RSO\" anchors the bottom-right corner as bold native brutalist lettering on the stark grainy poster.";
        String typographic = "Oversized condensed grotesk type fractures across three separate baselines over a field of "
                + "crushed film grain and marbled toner streaks, with no imagery at all. The headline \"BIG NIGHT - BERLIN\" "
                + "dominates the upper stack, the date \"7 Jun 2026\" cuts diagonally through the center in acid-green, and "
                + "\"Rso\" anchors a small monospaced credit block at the foot of the stark vertical machine-stamped composition "
                + "sheet, severe and rendered entirely as type with heavy xerox degradation eating every single glyph edge today.";
        PosterConcept c = new PosterConcept("brutalist_techno", "pal", List.of(
                new PosterVariant("people", people, "4:5", "Design"),
                new PosterVariant("object", object, "4:5", "Design"),
                new PosterVariant("typographic", typographic, "4:5", "Design")));
        assertThat(service.validate(c, request)).isNull();
    }

    @Test
    void validate_rejectsTooShortPrompt() {
        PosterConcept c = new PosterConcept("brutalist_techno", "pal", List.of(
                new PosterVariant("people", "a lone dancer under a flash", "4:5", "Design"),
                new PosterVariant("object", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c)).contains("too short");
    }

    @Test
    void validate_withRequest_rejectsVariantMissingRequiredText() {
        EventCreatorRequest request = brief();
        // Build prompts that satisfy shape (hero order, distinct, human-noun rules) and embed required text,
        // except the object variant omits the date.
        String people = "A lone dancer mid-spin under a hard flash, the crowd behind her smeared into motion blur in a "
                + "dark concrete room shot on grainy film. The bold headline \"BIG NIGHT - BERLIN\" stacks across the top, "
                + "the date \"7 JUN 2026\" locks beneath it and the venue \"RSO\" sits along the bottom edge as native "
                + "tracked-out acid-green poster typography filling the tall portrait frame end to end completely.";
        String objectMissingDate = "Monolithic concrete stairwell shot from directly below, raw formwork receding into a "
                + "black void under one burning industrial bulb, heavy photocopy grain across the vertical sheet. Vermilion "
                + "typography knocks out of shadow: the headline \"BIG NIGHT - BERLIN\" runs up the left edge and the venue "
                + "\"RSO\" anchors the bottom-right corner as bold native brutalist lettering on the stark grainy poster sheet today.";
        String typographic = "Oversized condensed grotesk type fractures across three baselines over crushed grain and toner "
                + "streaks, no imagery at all. The headline \"BIG NIGHT - BERLIN\" dominates the upper stack, the date "
                + "\"7 JUN 2026\" cuts diagonally through the center in acid-green, and \"RSO\" anchors a small monospaced "
                + "credit block at the foot of the stark vertical machine-stamped composition sheet rendered entirely as type.";
        PosterConcept c = new PosterConcept("brutalist_techno", "pal", List.of(
                new PosterVariant("people", people, "4:5", "Design"),
                new PosterVariant("object", objectMissingDate, "4:5", "Design"),
                new PosterVariant("typographic", typographic, "4:5", "Design")));

        String error = service.validate(c, request);
        assertThat(error).contains("missing required text");
        assertThat(error).contains("variant[1]");
        assertThat(error).contains("7 JUN 2026");
    }

    @Test
    void containsHumanHero_strictAboutPersonHero_allowsAtmosphericWords() {
        // type-led posters legitimately say "no people", "faces in the grain", "a faint silhouette"
        assertThat(AiEventDescriptionService.containsHumanHero(
                "no people, just type; faces in the grain and a faint silhouette behind the letters")).isFalse();
        assertThat(AiEventDescriptionService.containsHumanHero(
                "geometric figures and body text on a 4:5 portrait poster")).isFalse();
        // but a real person hero is caught
        assertThat(AiEventDescriptionService.containsHumanHero("a lone dancer dominates the frame")).isTrue();
        assertThat(AiEventDescriptionService.containsHumanHero("a young woman in mirror sunglasses")).isTrue();
    }

    // ---- T7: plan- and policy-driven validation -------------------------------------------------

    private static StyleCard cardWith(HumanPolicy policy, List<VariantSlot> plan) {
        return new StyleCard(
                "brutalist_techno", "mixed", List.of(new Rgb(13, 13, 13)),
                List.of("p"), List.of("o"), List.of("s"), List.of("ag"),
                List.of("c"), List.of("a"), List.of("tw"), List.of("ty"), List.of("ex"),
                policy, HumanStyle.PHOTOGRAPHIC, plan);
    }

    private static final List<VariantSlot> OBJ_ABS_TYPO = List.of(
            new VariantSlot(HeroType.OBJECT),
            new VariantSlot(HeroType.ABSTRACT_GRAPHIC),
            new VariantSlot(HeroType.TYPOGRAPHIC));

    // A human-less abstract-graphic hero prompt, distinct from objectPrompt()/typographicPrompt()
    // (no person-hero word ⇒ containsHumanHero == false).
    private static String abstractPrompt() {
        return "An edge-to-edge molten liquid-chrome field floods the whole sheet, acid-green ripples melting into "
                + "rose-pink over a near-black ground, oil-slick iridescence catching diffused glow with no discrete "
                + "subject anywhere in the frame. Heavy condensed white lettering reverses out of the haze along the "
                + "upper band while a tracked-out date line sits low and a thin lineup column slides down the right "
                + "margin, fine film grain and gentle gradient banding drifting across the dreamy atmospheric vertical poster.";
    }

    @Test
    void validate_forbidden_rejectsAnyHumanHero() {
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("object", peoplePrompt(), "4:5", "Design"),       // "a lone dancer..."
                new PosterVariant("abstract_graphic", abstractPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c, cardWith(HumanPolicy.FORBIDDEN, OBJ_ABS_TYPO)))
                .contains("forbids human heroes");
    }

    @Test
    void validate_forbidden_acceptsHumanLessRun() {
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("object", objectPrompt(), "4:5", "Design"),
                new PosterVariant("abstract_graphic", abstractPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c, cardWith(HumanPolicy.FORBIDDEN, OBJ_ABS_TYPO))).isNull();
    }

    @Test
    void validate_planOrderIsCheckedAgainstTheCardNotLegacyOrder() {
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("people", peoplePrompt(), "4:5", "Design"),
                new PosterVariant("abstract_graphic", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c, cardWith(HumanPolicy.RARE, OBJ_ABS_TYPO)))
                .contains("hero_type must be exactly \"object\"");
    }

    @Test
    void validate_rare_rejectsTwoHumanHeroes() {
        var plan = List.of(new VariantSlot(HeroType.OBJECT), new VariantSlot(HeroType.OBJECT),
                new VariantSlot(HeroType.TYPOGRAPHIC));
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("object", peoplePrompt(), "4:5", "Design"),
                new PosterVariant("object", peoplePrompt().replace("dancer", "woman").replace("crowd", "raver")
                        + " with distinct extra wording here today now to differ greatly", "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c, cardWith(HumanPolicy.RARE, plan)))
                .contains("at most one human-hero variant");
    }

    // ---- graceful degradation (no 502 over quality nits) ----------------------------------------

    private AiEventDescriptionService serviceReturning(String cannedJson) {
        return new AiEventDescriptionService(null, vibeLibrary, new PosterTextSpecFactory(),
                styleCardLibrary, new CreativeDirectionSampler()) {
            @Override
            String callLlm(String prompt) {
                return cannedJson;
            }
        };
    }

    @Test
    void generateConcept_returnsBestEffortWhenOnlyQualityChecksFail() {
        // Renderable (3 variants, valid hero types, non-empty) but fails quality (too short / not distinct):
        // must NOT 502 — render it best-effort.
        String json = "{\"sub_style_tag\":\"brutalist_techno\",\"color_palette_description\":\"black\",\"variants\":["
                + "{\"hero_type\":\"people\",\"ideogram_prompt\":\"a brutalist techno poster\",\"aspect_ratio\":\"1:1\",\"style_type\":\"Design\"},"
                + "{\"hero_type\":\"object\",\"ideogram_prompt\":\"a brutalist techno poster\",\"aspect_ratio\":\"1:1\",\"style_type\":\"Design\"},"
                + "{\"hero_type\":\"typographic\",\"ideogram_prompt\":\"a brutalist techno poster\",\"aspect_ratio\":\"1:1\",\"style_type\":\"Design\"}]}";

        AiEventDescriptionService.GeneratedConcept gc =
                serviceReturning(json).generateConcept(req("brutalist_techno"), 1L);

        assertThat(gc.concept().variants()).hasSize(3);
        assertThat(gc.concept().variants()).extracting(PosterVariant::aspectRatio)
                .containsExactly("4:5", "4:5", "4:5");  // forcePortrait still applied
    }

    @Test
    void generateConcept_throwsWhenConceptNeverRenderable() {
        String json = "{\"sub_style_tag\":\"brutalist_techno\",\"variants\":[]}";
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> serviceReturning(json).generateConcept(req("brutalist_techno"), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not produce a renderable PosterConcept")
                .hasMessageContaining("exactly 3 variants required");
    }

    @Test
    void validateRenderable_requiresThreeVariantsValidHeroTypesNonEmptyPrompts() {
        assertThat(service.validateRenderable(validConcept())).isNull();
        assertThat(service.validateRenderable(new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("people", peoplePrompt(), "4:5", "Design")))))
                .contains("exactly 3 variants");
        assertThat(service.validateRenderable(new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("bogus", peoplePrompt(), "4:5", "Design"),
                new PosterVariant("object", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")))))
                .contains("hero_type must be one of");
    }

    // ---- DJ-mode validation and prompt -----------------------------------------------------------

    /** A minimal request with no date/title/location so validate() skips the text-contract check. */
    private EventCreatorRequest reqNoText(String pinnedVibeId) {
        return new EventCreatorRequest(
                "vibe", "tone", "techno", "city",
                null, List.of("INSTAGRAM"),
                null, null, null, null, null, null,
                pinnedVibeId, null);
    }

    /** variant_plan [people, object, typographic], humanPolicy RARE — tests DJ bypass. */
    private static final StyleCard CARD_MIXED_PLAN_RARE =
            cardWith(HumanPolicy.RARE, List.of(
                    new VariantSlot(HeroType.PEOPLE),
                    new VariantSlot(HeroType.OBJECT),
                    new VariantSlot(HeroType.TYPOGRAPHIC)));

    /**
     * Three DJ-hero people prompts, each 60-180 words, pairwise distinct, each mentioning a DJ
     * and a human subject — the ONLY dimension under test is plan/policy, so every other
     * validator invariant is satisfied.
     */
    private static String djPeoplePrompt1() {
        return "The featured DJ mid-set behind the decks, hands resting on the mixer, bathed in a cold magenta wash "
                + "that grades into near-black at the edges of the tall vertical poster. Shot as a grainy high-contrast "
                + "photograph on real film, the DJ occupies the lower two-thirds while oversized condensed lettering "
                + "stacks across the top margin and the date locks beneath it in a tracked-out acid-green line. "
                + "Heavy photocopy grain eats into the shadow zones while a hard backlight rim separates the performer "
                + "from the crowd dissolving into motion blur behind, the whole composition anchored to a brutalist "
                + "concrete palette with one burning colour accent that bleeds through the headline band.";
    }

    private static String djPeoplePrompt2() {
        return "A monumental close-up portrait of the featured DJ, head and shoulders dominating the frame against "
                + "a crushed near-black field of marbled toner streaks, shot under a single overhead industrial lamp "
                + "that carves razor-sharp highlights across one cheekbone and shoulder. Oversized all-caps grotesk "
                + "letterforms occupy the lower third as bold reversed-out poster typography while the event date "
                + "sits in a tight tracked row at the very foot of the sheet. Halftone dot screen degradation covers "
                + "the entire vertical composition and acid-green accent lines slice between headline and body copy, "
                + "the performer's silhouette bleeding into the deep photographic shadow filling every corner edge.";
    }

    private static String djPeoplePrompt3() {
        return "The featured DJ silhouetted against a packed sweating crowd, arms raised above the decks inside "
                + "a dark warehouse space where a single wide beam of ultraviolet light cuts through the smoke and "
                + "catches the outline of the performer in electric violet against charcoal grey. Photographic medium "
                + "with heavy film grain and chromatic aberration fringing the edges of every figure. The headline "
                + "runs vertically up the right edge of this tall portrait format while the supporting date and venue "
                + "text is locked in a condensed sans-serif baseline along the bottom, and the brand colour floods "
                + "the background grade behind the silhouetted performer who stands at the visual centre of the poster.";
    }

    private PosterConcept djConcept() {
        return new PosterConcept("brutalist_techno", "near-black with magenta", List.of(
                new PosterVariant("people", djPeoplePrompt1(), "4:5", "Design"),
                new PosterVariant("people", djPeoplePrompt2(), "4:5", "Design"),
                new PosterVariant("people", djPeoplePrompt3(), "4:5", "Design")));
    }

    private SampledRun sampledDjRun() {
        return new CreativeDirectionSampler().sample(null, 42L, true);
    }

    @Test
    void djModeValidateAcceptsThreePeopleVariantsRegardlessOfCardPlanAndPolicy() {
        // a concept whose 3 variants are all hero_type "people" with DJ-led prompts,
        // against a card whose variant_plan is [people, object, typographic] and humanPolicy RARE
        String error = service.validate(djConcept(), reqNoText("brutalist_techno"), CARD_MIXED_PLAN_RARE, true);
        assertThat(error).isNull();
    }

    @Test
    void nonDjModeStillEnforcesTheCardPlan() {
        String error = service.validate(djConcept(), reqNoText("brutalist_techno"), CARD_MIXED_PLAN_RARE, false);
        assertThat(error).isNotNull(); // variant[1] hero_type mismatch (expected "object", got "people")
    }

    @Test
    void djModePromptContainsFeaturedDjBlockAfterBrandPalette() {
        String prompt = service.buildPrompt(briefWithBrand(), BRUTALIST, sampledDjRun(), null, true);
        int brandIdx = prompt.indexOf("BRAND PALETTE");
        int djIdx = prompt.indexOf("FEATURED DJ — MANDATORY");
        assertThat(brandIdx).isPositive();
        assertThat(djIdx).isGreaterThan(brandIdx);
        assertThat(prompt).contains("never facial features");
    }

    @Test
    void djModePromptWithoutBrandStillContainsFeaturedDjBlock() {
        String prompt = service.buildPrompt(brief(), BRUTALIST, sampledDjRun(), null, true);
        assertThat(prompt).doesNotContain("BRAND PALETTE");
        assertThat(prompt).contains("FEATURED DJ — MANDATORY");
    }

    // ---- T9: defensive JSON parsing -------------------------------------------------------------

    @Test
    void extractJson_stripsMarkdownFencesAndProse() {
        assertThat(AiEventDescriptionService.extractJson("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(AiEventDescriptionService.extractJson("Here is the JSON: {\"a\":1} thanks")).isEqualTo("{\"a\":1}");
        assertThat(AiEventDescriptionService.extractJson("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void parseConcept_parsesFencedHeroTypedJson() {
        String raw = "```json\n{\"sub_style_tag\":\"brutalist_techno\",\"color_palette_description\":\"black\","
                + "\"variants\":[{\"hero_type\":\"people\",\"ideogram_prompt\":\"p\",\"aspect_ratio\":\"4:5\",\"style_type\":\"Design\"}]}\n```";
        PosterConcept c = service.parseConcept(raw);
        assertThat(c.subStyleTag()).isEqualTo("brutalist_techno");
        assertThat(c.variants()).hasSize(1);
        assertThat(c.variants().get(0).heroType()).isEqualTo("people");
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
            return new UniversalRules(List.of("4:5"),
                    "No watermarks, logos, signatures, stock-template flyer layouts, or invented words.",
                    "never reference real venues, brands, or artists");
        }

        @Override
        public Vibe suggestForGenre(String genre) {
            return BRUTALIST;
        }
    }
}
