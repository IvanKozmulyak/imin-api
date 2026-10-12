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
                pinnedVibeId);
    }

    private EventCreatorRequest brief() {
        return new EventCreatorRequest(
                "warehouse rave", "energetic", "techno", "Berlin",
                LocalDate.of(2026, 6, 7), List.of("INSTAGRAM"),
                "DJ A, DJ B", "RSO", "BIG NIGHT - BERLIN", null,
                "Schnellerstrasse 137", "https://imin.wtf/e/big-night",
                "brutalist_techno");
    }

    private EventCreatorRequest briefWithBrand() {
        return new EventCreatorRequest(
                "warehouse rave", "energetic", "techno", "Berlin",
                LocalDate.of(2026, 6, 7), List.of("INSTAGRAM"),
                "DJ A, DJ B", "RSO", "BIG NIGHT - BERLIN",
                "#ec4899 (lead); supporting: #f6c04a, #a78bfa",
                "Schnellerstrasse 137", "https://imin.wtf/e/big-night",
                "brutalist_techno");
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
        assertThat(prompt).contains("hero_type: one of people | object | typographic | scene | abstract_graphic");
        assertThat(prompt).contains("\"BIG NIGHT - BERLIN\"");
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
    void buildPrompt_forcesFourByFive() {
        String prompt = service.buildPrompt(req("brutalist_techno"), BRUTALIST, sampled(), null);
        assertThat(prompt).contains("aspect_ratio: always exactly \"4:5\"");
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
    void validate_rejectsTooShortPrompt() {
        PosterConcept c = new PosterConcept("brutalist_techno", "pal", List.of(
                new PosterVariant("people", "a lone dancer under a flash", "4:5", "Design"),
                new PosterVariant("object", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c)).contains("too short");
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
                pinnedVibeId);
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
                + "photograph on real film, the DJ occupies the lower two-thirds while a wide band of empty black "
                + "space stretches across the top margin above a thin acid-green rule. "
                + "Heavy photocopy grain eats into the shadow zones while a hard backlight rim separates the performer "
                + "from the crowd dissolving into motion blur behind, the whole composition anchored to a brutalist "
                + "concrete palette with one burning colour accent that bleeds through the upper band.";
    }

    private static String djPeoplePrompt2() {
        return "A monumental close-up portrait of the featured DJ, head and shoulders dominating the frame against "
                + "a crushed near-black field of marbled toner streaks, shot under a single overhead industrial lamp "
                + "that carves razor-sharp highlights across one cheekbone and shoulder. The lower third stays a calm "
                + "matte-black field while a tight strip of cold light grazes the very foot of the sheet. Halftone dot "
                + "screen degradation covers the entire vertical composition and acid-green accent lines slice "
                + "diagonally across the shoulders, the performer's silhouette bleeding into the deep photographic "
                + "shadow filling every corner edge.";
    }

    private static String djPeoplePrompt3() {
        return "The featured DJ silhouetted against a packed sweating crowd, arms raised above the decks inside "
                + "a dark warehouse space where a single wide beam of ultraviolet light cuts through the smoke and "
                + "catches the outline of the performer in electric violet against charcoal grey. Photographic medium "
                + "with heavy film grain and chromatic aberration fringing the edges of every figure. The beam runs "
                + "vertically up the right edge of this tall portrait format while a low haze pools along the bottom, "
                + "and the brand colour floods the background grade behind the silhouetted performer who stands at "
                + "the visual centre of the poster.";
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
        assertThat(error).isNotNull().contains("hero_type"); // variant[1] hero_type mismatch (expected "object", got "people")
    }

    @Test
    void djModePromptContainsFeaturedDjBlockAfterBrandPalette() {
        String prompt = service.buildPrompt(briefWithBrand(), BRUTALIST, sampledDjRun(), null, true);
        int brandIdx = prompt.indexOf("BRAND PALETTE");
        int djIdx = prompt.indexOf("FEATURED DJ — MANDATORY");
        assertThat(brandIdx).isPositive();
        assertThat(djIdx).isGreaterThan(brandIdx);
        assertThat(prompt).contains("never facial features");
        assertThat(prompt).contains("Brand colours must dominate");
    }

    @Test
    void djModePromptWithoutBrandStillContainsFeaturedDjBlock() {
        String prompt = service.buildPrompt(brief(), BRUTALIST, sampledDjRun(), null, true);
        assertThat(prompt).doesNotContain("BRAND PALETTE");
        assertThat(prompt).contains("FEATURED DJ — MANDATORY");
        assertThat(prompt).doesNotContain("Brand colours must dominate");
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

    // ---- the LLM writes no poster text -------------------------------------------------------------

    private static final String SENTENCE = "A finished event poster whose only text is \"BIG NIGHT - BERLIN\" as the "
            + "huge headline, the date \"7 JUN 2026\" in large bold type directly under the title, and the venue "
            + "\"RSO\" in smaller type below the date.";

    private static String labelFreePeople() {
        return "A lone dancer mid-spin under one hard flash, the crowd dissolving into motion blur behind her inside a "
                + "dark concrete room, shot on grainy high-contrast black-and-white film for a tall vertical poster. "
                + "Oversized negative space opens across the top third while crushed shadows swallow every margin, "
                + "one acid-green accent streak cutting through the haze above her raised arms in the brutalist frame.";
    }

    private static String labelFreeObject() {
        return "Monolithic concrete stairwell viewed from directly below, raw formwork ribs receding into a black void "
                + "lit by a single burning industrial bulb, heavy photocopy grain and dust scratches degrading the whole "
                + "vertical sheet toward pure xerox texture. Vermilion-orange light knocks out of the deepest shadow and "
                + "pools along the lower margin of the stark composition, cold and severe from corner to corner.";
    }

    private static String labelFreeTypographic() {
        return "Oversized condensed grotesk letterforms fracture across three separate baselines, knocked clean out of "
                + "a field of crushed film grain and marbled toner streaks, with no imagery whatsoever, the type alone "
                + "forming the entire composition, set tight in all-caps with one acid-green accent line slicing "
                + "diagonally through the stack while dense xerox degradation eats the edges of every glyph on the stark sheet.";
    }

    private static String conceptJson(String typeStyle) {
        String ts = typeStyle == null ? "" : ",\"type_style\":\"" + typeStyle + "\"";
        return "{\"sub_style_tag\":\"brutalist_techno\",\"color_palette_description\":\"black\",\"variants\":["
                + "{\"hero_type\":\"people\",\"ideogram_prompt\":\"" + labelFreePeople() + "\",\"aspect_ratio\":\"4:5\"" + ts + "},"
                + "{\"hero_type\":\"object\",\"ideogram_prompt\":\"" + labelFreeObject() + "\",\"aspect_ratio\":\"4:5\"" + ts + "},"
                + "{\"hero_type\":\"typographic\",\"ideogram_prompt\":\"" + labelFreeTypographic() + "\",\"aspect_ratio\":\"4:5\"" + ts + "}]}";
    }

    // Catches: one of the two return paths (accepted, or best-effort after rejections) shipping render
    // prompts without the text sentence, i.e. posters with no date.
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "accepted|stamped condensed grotesk, all caps",
            "best-effort after rejections|"}, delimiter = '|')
    void generateConcept_everyRenderPromptStartsWithTheTextSentence(String path, String typeStyle) {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AiEventDescriptionService svc = new AiEventDescriptionService(null, vibeLibrary, new PosterTextSpecFactory(),
                styleCardLibrary, new CreativeDirectionSampler()) {
            @Override
            String callLlm(String prompt) {
                calls.incrementAndGet();
                return conceptJson(typeStyle);
            }
        };

        AiEventDescriptionService.GeneratedConcept gc = svc.generateConcept(brief(), 1L);

        // Accepted on the first call; a concept with no type_style is rejected every time and ships best-effort.
        assertThat(calls.get()).isEqualTo(typeStyle == null ? 2 : 1);

        assertThat(gc.concept().variants()).hasSize(3).allSatisfy(v -> {
            assertThat(v.ideogramPrompt()).startsWith(SENTENCE + " ");
            assertThat(v.ideogramPrompt()).endsWith("Every person shown is fully clothed.");
        });
        if (typeStyle != null) {
            assertThat(gc.concept().variants().get(0).ideogramPrompt())
                    .isEqualTo(SENTENCE + " " + labelFreePeople() + " Type: " + typeStyle + ". Every person shown is fully clothed.");
        }
    }

    private PosterConcept labelFreeConcept(String people, String typeStyle) {
        return new PosterConcept("brutalist_techno", "pal", List.of(
                new PosterVariant("people", people, "4:5", "condensed grotesk"),
                new PosterVariant("object", labelFreeObject(), "4:5", "condensed grotesk"),
                new PosterVariant("typographic", labelFreeTypographic(), "4:5", typeStyle)));
    }

    // Catches: labels and names the LLM writes get printed literally ("VENUE", "DITY", invented DJ text).
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "required string|, the words big night - berlin glow above her|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"BIG NIGHT - BERLIN\"",
            "DJ name|, DJ A spinning beside her|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"DJ A\"",
            "city in capitals|, BERLIN haze outside|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"BERLIN\"",
            "curly quote|, a \u201Cglow\u201D above her|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"\"\"",
            "single-quoted kicker|, 'YOUR CLUB PRESENTS' above her|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"'\"",
            "curly single quote|, \u2018SUMMER\u2019 above her|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"'\"",
            "kicker label|, a small kicker above her|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"kicker\"",
            "footer label|, a footer band below|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"footer\"",
            "qr code|, a QR code in the corner|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"qr code\"",
            "plural label|, small labels in the corners|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"labels\"",
            "plural headlines|, stacked headlines above|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"headlines\"",
            "quote mark|, a \"glow\" above her|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"\"\"",
            "layout label|, a venue line along the foot|condensed grotesk|variant[0].ideogram_prompt writes poster text: \"venue\"",
            "label in type_style|, arms high||variant[2].type_style writes poster text: \"headline\"",
            "blank type_style|, arms high|' '|variant[2].type_style is empty"
    }, delimiter = '|')
    void validate_rejectsLlmTextThatWritesPosterText(String name, String addition, String typeStyle, String error) {
        String people = labelFreePeople().replace(" in the brutalist frame.", addition + " in the brutalist frame.");
        String ts = typeStyle == null ? "bold condensed headline lettering" : typeStyle;

        assertThat(service.validate(labelFreeConcept(people, ts), brief())).isEqualTo(error);
    }

    @Test
    void validate_acceptsLabelFreeVisualsAndTypeStyle() {
        assertThat(service.validate(labelFreeConcept(labelFreePeople(), "condensed grotesk"), brief())).isNull();
    }

    private static EventCreatorRequest briefAt(String venue, String city) {
        return new EventCreatorRequest("warehouse rave", "energetic", "techno", city,
                LocalDate.of(2026, 6, 7), List.of("INSTAGRAM"), "DJ A, DJ B", venue, "BIG NIGHT - BERLIN",
                null, null, null, "brutalist_techno");
    }

    // Catches: ordinary visual words rejected as poster text, so good concepts fall to best-effort.
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "hyphenated label in type_style|, arms high|title-case caps, headline-weight grotesque",
            "hyphenated label in visual|, a logo-like sticker on her jacket|condensed grotesk",
            "city skyline|, a city skyline at night behind her|condensed grotesk",
            "one-word venue as a noun|, a warehouse doorway behind her|condensed grotesk",
            "one-word city as an adjective|, nice warm haze behind her|condensed grotesk",
            "one-word city capitalised|, Nice haze behind her|condensed grotesk",
            "apostrophe possessive|, the club's mirrored ceiling above her|condensed grotesk",
            "apostrophe compound|, a worm's-eye angle on her|condensed grotesk",
            "plural possessive|, the dancers' raised arms around her|condensed grotesk",
            "curly apostrophe|, the club\u2019s haze behind her|condensed grotesk",
            "straight-quoted decade|, a '90s rave haze behind her|condensed grotesk",
            "curly closing-quoted decade|, an \u201980s chrome sheen behind her|condensed grotesk",
            "curly opening-quoted decade|, an \u201880s chrome sheen behind her|condensed grotesk",
            "label inside a compound|, a warning-label stripe behind her|condensed grotesk"
    }, delimiter = '|')
    void validate_acceptsOrdinaryVisualWords(String name, String addition, String typeStyle) {
        String people = labelFreePeople().replace(" in the brutalist frame.", addition + " in the brutalist frame.");

        assertThat(service.validate(labelFreeConcept(people, typeStyle), briefAt("Warehouse", "Nice"))).isNull();
    }

    // Catches: quoted sample text in a style-card hint (or the dropped example prompt) copied by the
    // LLM and printed as invented words. Slot words are kept out at the source (StyleCardVisualOnlyTest).
    @Test
    void buildPrompt_hintsCarryNoQuotedText() {
        SampledRun run = new SampledRun(List.of(
                new CreativeDirection(HeroType.PEOPLE, "lone dancer under 'HARD FLASH' light", "giant lettering top",
                        "grain reading \u2018LOUD\u2019", "black", "stamped grotesk reading \"NIGHT FORM\""),
                new CreativeDirection(HeroType.OBJECT, "stairwell", "object low, empty space above",
                        "dust", "black 'INK' wash", "outline caps over a \u201Csolid\u201D band"),
                new CreativeDirection(HeroType.TYPOGRAPHIC, null, "type across three baselines",
                        "xerox", "mono", "mono caps")),
                "Headline reads \"NIGHT FORM\" with \"22.11.2025\" and \"DOORS 23:00\" below.");

        String prompt = service.buildPrompt(brief(), BRUTALIST, run, null);

        List<String> hints = prompt.lines().filter(l -> l.startsWith("- Layout: ") || l.startsWith("- Palette: ")
                || l.startsWith("- Hero subject: ") || l.startsWith("- Treatment: ")).toList();
        assertThat(hints).hasSize(10).allSatisfy(l ->
                assertThat(AiEventDescriptionService.posterTextIn(l.substring(l.indexOf(": ") + 2), List.of())).isNull());
        assertThat(prompt).doesNotContain("NIGHT FORM").doesNotContain("DOORS 23:00").doesNotContain("HARD FLASH")
                .doesNotContain("LOUD").doesNotContain("INK").doesNotContain("solid");
    }

    // Catches: after two rejected attempts the best-effort concept still carries the LLM's quoted
    // words, which the renderer prints next to the real event text (invented words on the poster).
    @Test
    void generateConcept_bestEffort_stripsQuotedTextBeforeComposing() {
        String quoted = labelFreePeople().replace(" in the brutalist frame.",
                ", \\\"NIGHT FORM\\\" sprayed above her and \u201CDOORS 23:00\u201D below, 'YOUR CLUB PRESENTS' "
                        + "and \u2018SUMMER\u2019 under the club's ceiling in the brutalist frame.");
        String json = conceptJson("stamped grotesk").replace(labelFreePeople(), quoted)
                .replace("\"type_style\":\"stamped grotesk\"}", "\"type_style\":\"\\\"XYZ\\\" stamped grotesk\"}");

        AiEventDescriptionService.GeneratedConcept gc = serviceReturning(json).generateConcept(brief(), 1L);

        assertThat(gc.concept().variants()).allSatisfy(v -> {
            String afterSentence = v.ideogramPrompt().substring(SENTENCE.length());
            assertThat(afterSentence).doesNotContain("\"").doesNotContain("\u201C").doesNotContain("\u201D")
                    .doesNotContain("NIGHT FORM").doesNotContain("DOORS 23:00").doesNotContain("XYZ")
                    .doesNotContain("YOUR CLUB PRESENTS").doesNotContain("SUMMER").doesNotContain("\u2018");
        });
        assertThat(gc.concept().variants().get(0).ideogramPrompt())
                .contains("sprayed above her and below, and under the club's ceiling");
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
