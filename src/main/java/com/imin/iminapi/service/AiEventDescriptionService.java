package com.imin.iminapi.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.HumanPolicy;
import com.imin.iminapi.dto.HumanStyle;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterTextSpec;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.VariantSlot;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.service.ai.CreativeDirection;
import com.imin.iminapi.service.ai.CreativeDirectionSampler;
import com.imin.iminapi.service.ai.CreativeDirectionSampler.SampledRun;
import com.imin.iminapi.service.ai.PromptDistinctness;
import com.imin.iminapi.service.poster.PosterTextSpecFactory;
import com.imin.iminapi.service.poster.StyleCardLibrary;
import com.imin.iminapi.service.poster.VibeLibrary;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns an event brief into a {@link PosterConcept} (a chosen vibe + 3 hero-typed variants) via one
 * LLM call. The art director (stage-1 LLM) is given the vibe's visual world + a code-sampled
 * {@link CreativeDirection} per variant (drawn from the vibe's style-card pools), so the three
 * variants are concrete, subject-led, and distinct from each other and from run to run.
 *
 * <p>No diffusion negative tokens are injected: this prompt drives an instruction-following LLM, so
 * "AVOID deformed faces"-style vocabulary (which a diffusion model reads as a negation but an LLM
 * reads as "avoid faces") is gone. Variation comes from {@link CreativeDirectionSampler}, not from
 * asking the model to "be creative".
 */
@Service
@RequiredArgsConstructor
public class AiEventDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(AiEventDescriptionService.class);

    private final ChatClient chatClient;
    private final VibeLibrary vibeLibrary;
    private final PosterTextSpecFactory posterTextSpecFactory;
    private final StyleCardLibrary styleCardLibrary;
    private final CreativeDirectionSampler creativeDirectionSampler;

    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Target social ratios; every variant is forced to 4:5 downstream regardless.
    private static final Set<String> VALID_ASPECTS = Set.of("4:5", "1:1", "9:16", "16:9");
    private static final Pattern WORDS = Pattern.compile("\\s+");
    private static final int MIN_WORDS = 60;
    private static final int MAX_WORDS = 180;
    private static final int MAX_ATTEMPTS = 2;
    /** Every variant renders in this single portrait format. */
    private static final String FORCED_ASPECT_RATIO = "4:5";
    /** Three prompts must not be paraphrases: pairwise word-trigram Jaccard must stay below this. */
    private static final double DISTINCTNESS_THRESHOLD = 0.45;

    /** Person words the PEOPLE variant must contain — broad, because real heroes are described many ways. */
    static final List<String> HUMAN_NOUNS = List.of(
            "figure", "dancer", "crowd", "portrait", "silhouette", "face", "body", "person", "people",
            "woman", "man", "girl", "boy", "guy", "raver", "clubber", "club-goer", "partygoer", "reveler",
            "dj", "model", "performer", "audience", "friend", "hand", "head");
    /** Lenient: anything indicating a person — used for the people-variant requirement. */
    private static final Pattern HUMAN_SUBJECT = Pattern.compile(
            "\\b(figures?|dancers?|crowds?|portraits?|silhouettes?|faces?|bodies|body|persons?|people"
            + "|wom[ae]n|m[ae]n|girls?|boys?|guys?|ravers?|clubbers?|club-?goers?|party-?goers?|revell?ers?"
            + "|djs?|models?|performers?|audience|friends?|hands?|heads?)\\b",
            Pattern.CASE_INSENSITIVE);
    /**
     * Strict: only a person rendered as the HERO — used for the typographic exclusion. Deliberately omits
     * atmospheric/ambiguous tokens (silhouette, face, figure, body, hand, head, crowd) and negation-prone
     * ones (people, person), since a type-led poster legitimately mentions "no people", "faces in the
     * grain", or "geometric figures" without being person-led. A hard person-hero word here is the signal.
     */
    private static final Pattern HUMAN_HERO = Pattern.compile(
            "\\b(dancers?|portraits?|wom[ae]n|m[ae]n|girls?|boys?|ravers?|clubbers?|club-?goers?"
            + "|party-?goers?|revell?ers?|djs?|models?|performers?)\\b",
            Pattern.CASE_INSENSITIVE);
    /**
     * Phrases where a human token actually describes layout/typography, not a person — e.g.
     * "4:5 portrait poster" (orientation), "body text"/"body copy" (typography), "geometric figures"
     * (abstract shapes). Neutralized before both checks so a compliant typographic prompt that names the
     * 4:5 portrait format is not mistaken for a person, and a people prompt is not satisfied by orientation
     * vocabulary alone.
     */
    private static final Pattern DESIGN_VOCAB = Pattern.compile(
            "\\b(?:"
            + "portrait\\s+(?:poster|orientation|format|frame|crop|mode|layout|aspect|ratio|sheet|composition)"
            + "|(?:\\d+\\s*:\\s*\\d+|tall|vertical|upright)\\s+portrait"
            + "|body\\s+(?:text|copy|of|type|font)"
            + "|(?:geometric|abstract|stick|line)\\s+figures?"
            + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /** True when the prompt names a person at all (lenient), ignoring orientation/typography vocabulary. */
    static boolean mentionsHumanSubject(String prompt) {
        return matchesAfterDesignStrip(prompt, HUMAN_SUBJECT);
    }

    /** True when the prompt names a person as the hero (strict), ignoring orientation/typography vocabulary. */
    static boolean containsHumanHero(String prompt) {
        return matchesAfterDesignStrip(prompt, HUMAN_HERO);
    }

    private static boolean matchesAfterDesignStrip(String prompt, Pattern pattern) {
        if (prompt == null) return false;
        String cleaned = DESIGN_VOCAB.matcher(prompt).replaceAll(" ");
        return pattern.matcher(cleaned).find();
    }

    /** The concept plus the creative directions sampled for it (carried into the orchestrator). */
    public record GeneratedConcept(PosterConcept concept, List<CreativeDirection> directions) {}

    public GeneratedConcept generateConcept(EventCreatorRequest request, long seed) {
        Vibe vibe = resolveVibe(request);
        StyleCard card = styleCardLibrary.get(vibe.id()).orElse(null);
        SampledRun sampled = creativeDirectionSampler.sample(card, seed);

        String reinforcement = null;
        PosterConcept lastRenderable = null;
        String lastError = "no concept produced";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String prompt = buildPrompt(request, vibe, sampled, reinforcement);
            log.info("[LLM concept] prompt sent (attempt {}/{}):\n{}", attempt, MAX_ATTEMPTS, prompt);
            PosterConcept concept = parseConcept(callLlm(prompt));

            String renderableError = validateRenderable(concept);
            if (renderableError == null) {
                lastRenderable = concept;   // keep the most recent structurally-valid concept as a fallback
                String validationError = validate(concept, request, card);
                if (validationError == null) {
                    log.debug("Concept accepted on attempt {}: vibe={}, variants={}",
                            attempt, concept.subStyleTag(), concept.variants().size());
                    return new GeneratedConcept(forcePortrait(concept), sampled.directions());
                }
                lastError = validationError;
            } else {
                lastError = renderableError;
            }
            reinforcement = "Previous attempt rejected: " + lastError;
            log.warn("Concept rejected (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, lastError);
        }

        // Don't fail the whole pipeline over quality nits: if the last concept is structurally renderable
        // (3 variants, valid hero types, non-empty prompts), render it best-effort. The downstream
        // legibility + style validation gates are the real per-image quality backstop.
        if (lastRenderable != null) {
            log.warn("Returning best-effort concept after {} attempts (residual issue: {})", MAX_ATTEMPTS, lastError);
            return new GeneratedConcept(forcePortrait(lastRenderable), sampled.directions());
        }
        throw new IllegalStateException(
                "Could not produce a renderable PosterConcept after " + MAX_ATTEMPTS + " attempts: " + lastError);
    }

    /** The stage-1 LLM call. Package-private seam so the validation/degradation loop is unit-testable. */
    String callLlm(String prompt) {
        return chatClient.prompt().user(prompt).call().content();
    }

    /**
     * The minimum a concept needs to be rendered into the three posters, so quality issues degrade to a
     * best-effort render rather than failing the whole request: exactly 3 variants, each with a non-blank
     * prompt and a hero_type the renderer/style gate can read.
     */
    String validateRenderable(PosterConcept concept) {
        if (concept == null) return "null concept";
        List<PosterVariant> variants = concept.variants();
        if (variants == null || variants.size() != 3) return "exactly 3 variants required";
        for (int i = 0; i < variants.size(); i++) {
            PosterVariant v = variants.get(i);
            if (v.ideogramPrompt() == null || v.ideogramPrompt().isBlank()) {
                return "variant[" + i + "].ideogram_prompt is empty";
            }
            if (HeroType.fromWire(v.heroType()) == null) {
                return "variant[" + i + "].hero_type must be one of people, object, typographic, scene, abstract_graphic";
            }
        }
        return null;
    }

    /**
     * Parse the model's reply into a {@link PosterConcept}. Tolerant of the new art-director model:
     * strips markdown fences and any prose around the JSON object before deserializing.
     */
    PosterConcept parseConcept(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Art-director LLM returned an empty response");
        }
        String json = extractJson(raw);
        try {
            return objectMapper.readValue(json, PosterConcept.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse PosterConcept JSON from LLM response", e);
        }
    }

    /** Strip ```json fences / surrounding prose; fall back to the outermost {...} span. */
    static String extractJson(String raw) {
        String t = raw.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl >= 0) t = t.substring(firstNl + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);
            t = t.trim();
        }
        if (!t.startsWith("{")) {
            int open = t.indexOf('{');
            int close = t.lastIndexOf('}');
            if (open >= 0 && close > open) t = t.substring(open, close + 1);
        }
        return t;
    }

    /** Resolve the style card backing a concept's vibe (null when the vibe has no card). */
    private StyleCard cardFor(PosterConcept concept) {
        return concept == null ? null : styleCardLibrary.get(concept.subStyleTag()).orElse(null);
    }

    String validate(PosterConcept concept) {
        return validate(concept, cardFor(concept));
    }

    String validate(PosterConcept concept, StyleCard card) {
        if (concept == null) return "null concept";
        if (concept.subStyleTag() == null || !vibeLibrary.hasVibe(concept.subStyleTag())) {
            return "sub_style_tag must be a known vibe id";
        }
        List<PosterVariant> variants = concept.variants();
        if (variants == null || variants.size() != 3) return "exactly 3 variants required";

        List<HeroType> plan = planModes(card);
        HumanPolicy policy = (card == null || card.humanPolicy() == null) ? HumanPolicy.REQUIRED : card.humanPolicy();

        for (int i = 0; i < variants.size(); i++) {
            PosterVariant v = variants.get(i);
            String expectedWire = plan.get(i).wire();
            if (v.heroType() == null || !expectedWire.equals(v.heroType().trim().toLowerCase())) {
                return "variant[" + i + "].hero_type must be exactly \"" + expectedWire + "\" (planned: " + planWire(plan) + ")";
            }
            if (v.aspectRatio() == null || !VALID_ASPECTS.contains(v.aspectRatio())) {
                return "variant[" + i + "].aspect_ratio must be one of " + VALID_ASPECTS;
            }
            if (!"Design".equals(v.styleType())) {
                return "variant[" + i + "].style_type must be \"Design\"";
            }
            String p = v.ideogramPrompt();
            if (p == null || p.isBlank()) return "variant[" + i + "].ideogram_prompt is empty";
            int wc = wordCount(p);
            if (wc < MIN_WORDS) return "variant[" + i + "].ideogram_prompt too short (" + wc + " words, min " + MIN_WORDS + ")";
            if (wc > MAX_WORDS) return "variant[" + i + "].ideogram_prompt too long (" + wc + " words, max " + MAX_WORDS + ")";
        }

        // Mode-driven per-slot human rules: a people slot must show a human; a typographic slot must
        // not be built around a person (type is the image).
        for (int i = 0; i < variants.size(); i++) {
            HeroType mode = plan.get(i);
            String p = variants.get(i).ideogramPrompt();
            if (mode == HeroType.PEOPLE && !mentionsHumanSubject(p)) {
                return "variant[" + i + "] (people) must contain a human subject (one of " + HUMAN_NOUNS + ")";
            }
            if (mode == HeroType.TYPOGRAPHIC && containsHumanHero(p)) {
                return "variant[" + i + "] (typographic) must not be built around a person — typography is the image";
            }
        }

        // Policy caps on human heroes across the whole run.
        long humanHeroes = variants.stream().filter(v -> containsHumanHero(v.ideogramPrompt())).count();
        if (policy == HumanPolicy.FORBIDDEN && humanHeroes > 0) {
            return "this vibe forbids human heroes, but a person appears in a variant";
        }
        if (policy == HumanPolicy.RARE && humanHeroes > 1) {
            return "this vibe allows at most one human-hero variant, but " + humanHeroes + " contain a person";
        }
        if (policy == HumanPolicy.REQUIRED
                && variants.stream().noneMatch(v -> mentionsHumanSubject(v.ideogramPrompt()))) {
            return "this vibe requires a human hero, but no variant contains a person";
        }

        // The three prompts must be visually distinct, not paraphrases of each other.
        List<String> prompts = variants.stream().map(PosterVariant::ideogramPrompt).toList();
        if (!PromptDistinctness.allPairwiseBelow(prompts, DISTINCTNESS_THRESHOLD)) {
            return "variants too similar — rewrite with different sentence structures, subjects, and layouts";
        }
        return null;
    }

    /** The 3 planned hero modes for a card, or the legacy order when the card has no plan. */
    private static List<HeroType> planModes(StyleCard card) {
        if (card == null || card.variantPlan() == null || card.variantPlan().isEmpty()) {
            return List.of(HeroType.ORDER);
        }
        return card.variantPlan().stream().map(VariantSlot::mode).toList();
    }

    private static String planWire(List<HeroType> plan) {
        return plan.stream().map(HeroType::wire).reduce((a, b) -> a + ", " + b).orElse("");
    }

    String validate(PosterConcept concept, EventCreatorRequest request) {
        return validate(concept, request, cardFor(concept));
    }

    String validate(PosterConcept concept, EventCreatorRequest request, StyleCard card) {
        String shapeError = validate(concept, card);
        if (shapeError != null) return shapeError;

        PosterTextSpec textSpec = posterTextSpecFactory.from(request);
        if (textSpec.required().isEmpty()) return null;

        List<PosterVariant> variants = concept.variants();
        for (int i = 0; i < variants.size(); i++) {
            // Case-insensitive: the model often re-cases the title/date in prose (e.g. "7 Jun 2026" vs the
            // contract's "7 JUN 2026"); the downstream vision legibility gate is the exact-rendering check.
            String prompt = variants.get(i).ideogramPrompt().toLowerCase(java.util.Locale.ROOT);
            for (String requiredText : textSpec.required()) {
                if (!prompt.contains(requiredText.toLowerCase(java.util.Locale.ROOT))) {
                    return "variant[" + i + "].ideogram_prompt missing required text \"" + requiredText + "\"";
                }
            }
        }
        return null;
    }

    int wordCount(String s) {
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return 0;
        return WORDS.split(trimmed).length;
    }

    /** Force every variant to the single {@value #FORCED_ASPECT_RATIO} portrait, preserving everything else. */
    PosterConcept forcePortrait(PosterConcept concept) {
        List<PosterVariant> portrait = concept.variants().stream()
                .map(v -> new PosterVariant(
                        v.heroType(), v.ideogramPrompt(), FORCED_ASPECT_RATIO, v.styleType()))
                .toList();
        return new PosterConcept(concept.subStyleTag(), concept.colorPaletteDescription(), portrait);
    }

    /** Resolve the vibe driving this concept: the pinned vibe id, else auto-suggested from genre. */
    Vibe resolveVibe(EventCreatorRequest request) {
        return vibeLibrary.byId(request.subStyleTag())
                .orElseGet(() -> vibeLibrary.suggestForGenre(request.genre()));
    }

    String buildPrompt(EventCreatorRequest request, Vibe vibe, SampledRun sampled, String reinforcement) {
        PosterTextSpec textSpec = posterTextSpecFactory.from(request);
        List<CreativeDirection> d = sampled.directions();
        HumanStyle humanStyle = styleCardLibrary.get(vibe.id())
                .map(StyleCard::humanStyle).orElse(null);
        String example = sampled.examplePrompt();

        StringBuilder sb = new StringBuilder();
        sb.append("You are an art director creating ONE-OF-A-KIND nightlife event posters. Your output drives ")
          .append("Recraft image generation with native typography integrated into the artwork. Tonight's three ")
          .append("concepts must be visually distinct from each other and from any generic flyer.\n\n");

        sb.append("VIBE — every variant stays inside this visual world:\n")
          .append("    Visual style: ").append(vibe.visualStyle()).append("\n")
          .append("    Typography: ").append(vibe.typography()).append("\n")
          .append("    Composition feel: ").append(vibe.composition()).append("\n");
        if (notBlank(vibe.subject())) {
            sb.append("Hero subject matter for this vibe: ").append(vibe.subject()).append("\n");
        }
        sb.append("\n");

        sb.append("CREATIVE DIRECTIONS — one per variant, follow them precisely:\n\n");
        for (int i = 0; i < d.size(); i++) {
            sb.append(variantBlock(i + 1, d.get(i), vibe));
        }
        sb.append("\n");

        if (notBlank(example)) {
            sb.append("Example of the caliber expected (different event — do NOT copy its content or sentence structure):\n")
              .append("\"").append(example).append("\"\n\n");
        }

        sb.append("Return a JSON object with exactly these fields:\n")
          .append("- sub_style_tag: must be exactly \"").append(vibe.id()).append("\"\n")
          .append("- color_palette_description: brief human-readable description of the dominant colors\n")
          .append("- variants: exactly 3 objects, each with:\n")
          .append("    - hero_type: one of people | object | typographic | scene | abstract_graphic, matching the variant role above\n")
          .append("    - ideogram_prompt: a COMPLETE self-contained Recraft prompt, 60-180 words, following this exact anatomy in order:\n")
          .append("      1. hero subject (or type-as-image for typographic) with concrete visual nouns\n")
          .append("      2. medium (photograph / photocopy / collage / illustration — per the treatment)\n")
          .append("      3. layout and crop for a 4:5 vertical poster (taller than wide)\n")
          .append("      4. lighting\n")
          .append("      5. colors\n")
          .append("      6. typography treatment and where each text element sits\n")
          .append("      7. the required text, integrated as native poster typography\n")
          .append("    - aspect_ratio: always exactly \"4:5\"\n")
          .append("    - style_type: always \"Design\"\n\n");

        sb.append("STRICT RULES for each ideogram_prompt:\n")
          .append("- A finished event poster, not a background plate, not a mockup\n")
          .append("- An image-led poster where the hero dominates and the required text is integrated as bold native typography\n")
          .append("- The three prompts must not share opening words, sentence structure, or layout\n")
          .append("- Never open with \"Create a\", \"Design a\", or \"Craft a\" — start with the visual content itself\n")
          .append(humanRule(humanStyle)).append("\n")
          .append("- ").append(notBlank(universalNegative()) ? universalNegative()
                  : "No watermarks, logos, signatures, stock-template flyer layouts, or invented words.").append("\n\n");

        sb.append(textSpec.forPrompt()).append("\n");

        String ipRule = vibeLibrary.universalRules() == null ? null : vibeLibrary.universalRules().ipRule();
        if (notBlank(ipRule)) {
            sb.append("IP rule: ").append(ipRule).append("\n\n");
        }

        sb.append("Event brief:\n")
          .append("- vibe: ").append(request.vibe()).append("\n")
          .append("- tone: ").append(request.tone()).append("\n")
          .append("- genre: ").append(request.genre()).append("\n")
          .append("- city: ").append(request.city()).append("\n")
          .append("- date: ").append(request.date()).append("\n");
        if (request.title()    != null) sb.append("- title:    \"").append(request.title()).append("\"\n");
        if (request.djName()   != null) sb.append("- djName:   \"").append(request.djName()).append("\"\n");
        if (request.location() != null) sb.append("- venue:    \"").append(request.location()).append("\"\n");
        if (request.accentColor() != null) sb.append("- accentColor: ").append(request.accentColor()).append("\n");

        sb.append("\nReturn ONLY the JSON object, with no markdown fences or commentary.\n");
        if (notBlank(reinforcement)) {
            sb.append("\n").append(reinforcement).append("\nFix the issue above and return valid JSON only.\n");
        }
        return sb.toString();
    }

    private static String variantBlock(int n, CreativeDirection dir, Vibe vibe) {
        HeroType mode = dir.heroType();
        boolean typographic = mode == HeroType.TYPOGRAPHIC;
        StringBuilder b = new StringBuilder();
        b.append("Variant ").append(n).append(" — hero_type \"").append(mode.wire()).append("\":\n");
        if (typographic) {
            b.append("- No pictorial hero: typography IS the image. Texture: ")
             .append(nv(dir.accent(), "expressive type texture and grain")).append("\n");
        } else {
            b.append("- Hero subject: ").append(nv(dir.heroSubject(), vibe.subject())).append("\n");
            b.append("- Treatment: ").append(nv(dir.accent(), "in-keeping texture")).append("\n");
        }
        b.append("- Layout: ").append(nv(dir.composition(), vibe.composition())).append("\n");
        b.append("- Palette: ").append(nv(dir.paletteTwist(), "the vibe palette")).append("; type: ")
         .append(nv(dir.typeTreatment(), vibe.typography())).append("\n\n");
        return b.toString();
    }

    /** The strict human-rendering rule, conditioned on the vibe's human_style (null ⇒ photographic). */
    private static String humanRule(HumanStyle humanStyle) {
        if (humanStyle == HumanStyle.ABSTRACTED) {
            return "- Render any human figure abstracted — motion-blur, halftone, or silhouette — never a clean centered portrait";
        }
        if (humanStyle == HumanStyle.FIGURE_AS_OBJECT) {
            return "- Any human form must be a sculpted/rendered material object (chrome, wireframe, posterized), never a photographed person";
        }
        return "- Any people must be rendered photorealistically with correct anatomy; faces may be cropped, motion-blurred, or in shadow for style";
    }

    private String universalNegative() {
        return vibeLibrary.universalRules() == null ? null : vibeLibrary.universalRules().negativePrompt();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nv(String value, String fallback) {
        return notBlank(value) ? value : (fallback == null ? "" : fallback);
    }
}
