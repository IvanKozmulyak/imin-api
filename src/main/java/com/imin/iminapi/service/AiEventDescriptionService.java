package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.UniversalRules;
import com.imin.iminapi.dto.Vibe;
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
 * Turns an event brief into a {@link PosterConcept} (a chosen vibe + 3 variants) via one LLM call.
 * Vibe-only: the concept's {@code sub_style_tag} is a {@link VibeLibrary} vibe id, and the prompt is
 * assembled deterministically from that vibe's structured preset (palette / typography / composition
 * / avoid) plus the universal negative prompt + IP rule. There is no legacy aesthetic-tag path.
 */
@Service
@RequiredArgsConstructor
public class AiEventDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(AiEventDescriptionService.class);

    private final ChatClient chatClient;
    private final VibeLibrary vibeLibrary;

    private static final Set<String> VALID_VARIANT_STYLES = Set.of("atmospheric", "graphic", "minimal");
    // Target social ratios: 4:5 (poster), 1:1 (feed), 9:16 (story/reel), 16:9 (landscape/OG ≈ 1.91:1).
    private static final Set<String> VALID_ASPECTS = Set.of("4:5", "1:1", "9:16", "16:9");
    private static final Pattern WORDS = Pattern.compile("\\s+");
    private static final int MIN_WORDS = 30;
    private static final int MAX_WORDS = 150;
    private static final int MAX_ATTEMPTS = 2;
    private static final String GENERIC_POSTER_NEGATIVE_PROMPT =
            "stock flyer layout, centered generic object, obvious music iconography, clipart, "
                    + "generic neon crowd, template poster, bland gradient background";

    public PosterConcept generateConcept(EventCreatorRequest request) {
        String reinforcement = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            PosterConcept concept = chatClient.prompt()
                    .user(buildPrompt(request, reinforcement))
                    .call()
                    .entity(PosterConcept.class);
            String validationError = validate(concept);
            if (validationError == null) {
                log.debug("Concept generated on attempt {}: vibe={}, variants={}",
                        attempt, concept.subStyleTag(), concept.variants().size());
                return concept;
            }
            reinforcement = "Previous attempt rejected: " + validationError;
            log.warn("Concept rejected (attempt {}): {}", attempt, validationError);
        }
        throw new IllegalStateException("Could not produce a valid PosterConcept after " + MAX_ATTEMPTS + " attempts");
    }

    String validate(PosterConcept concept) {
        if (concept == null) return "null concept";
        if (concept.subStyleTag() == null || !vibeLibrary.hasVibe(concept.subStyleTag())) {
            return "sub_style_tag must be a known vibe id";
        }
        List<PosterVariant> variants = concept.variants();
        if (variants == null || variants.size() != 3) return "exactly 3 variants required";
        for (int i = 0; i < variants.size(); i++) {
            PosterVariant v = variants.get(i);
            if (v.variantStyle() == null || !VALID_VARIANT_STYLES.contains(v.variantStyle())) {
                return "variant[" + i + "].variant_style must be one of " + VALID_VARIANT_STYLES;
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
        return null;
    }

    int wordCount(String s) {
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return 0;
        return WORDS.split(trimmed).length;
    }

    /** Resolve the vibe driving this concept: the pinned vibe id, else auto-suggested from genre. */
    Vibe resolveVibe(EventCreatorRequest request) {
        return vibeLibrary.byId(request.subStyleTag())
                .orElseGet(() -> vibeLibrary.suggestForGenre(request.genre()));
    }

    String buildPrompt(EventCreatorRequest request, String reinforcement) {
        Vibe vibe = resolveVibe(request);
        StringBuilder sb = new StringBuilder();
        sb.append("You are an art director for a nightlife event poster. Your output drives ")
          .append("Ideogram V3, a text-in-image model (~90-95% accuracy on quoted strings).\n\n")
          .append("Return a JSON object with exactly these fields:\n")
          .append("- sub_style_tag: must be exactly \"").append(vibe.id())
          .append("\". Render EVERY variant in this exact visual style:\n")
          .append(vibeStyleBlock(vibe))
          .append("- color_palette_description: a brief human-readable description of the dominant colors\n")
          .append("- variants: exactly 3 objects, each with:\n")
          .append("    - variant_style: one of atmospheric, graphic, minimal\n")
          .append("    - ideogram_prompt: a COMPLETE self-contained prompt, 30-150 words, describing the scene and the designed EMPTY typographic surfaces where the platform will later place exact event text\n")
          .append("    - aspect_ratio: one of 4:5, 1:1, 9:16, 16:9\n")
          .append("    - style_type: always \"Design\"\n\n")
          .append("STRICT RULES for each ideogram_prompt:\n")
          .append("- Do NOT render factual event text, dates, venues, prices, DJ names, URLs, QR codes, or readable words\n")
          .append("- Instead design empty text surfaces that feel native to the poster: sticker plates, raw headline zones, stamped rails, torn labels, engraved corner marks, or glowing badges\n")
          .append("- No letters, no glyphs, no pseudo-text, no abstract alphabets, no decorative writing, no readable or unreadable word-like marks\n")
          .append("- Describe blank typography integration surfaces explicitly (e.g. empty chrome sticker plate, distressed stamped rail, raw-print headline zone, engraved minimal corner marks)\n")
          .append("- Never name real venues, real brands, real DJs, or event-specific text in the artwork\n")
          .append("- End the prompt with: \"no factual text, blank designed text surfaces only\"\n\n");
        appendUniversalRules(sb);
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
        if (reinforcement != null && !reinforcement.isBlank()) {
            sb.append("\n").append(reinforcement).append("\nFix the issue above and return valid JSON only.\n");
        }
        return sb.toString();
    }

    /** Deterministic structured style block for the vibe — the building blocks of the prompt. */
    private static String vibeStyleBlock(Vibe v) {
        StringBuilder b = new StringBuilder();
        b.append("    Visual style: ").append(v.visualStyle()).append("\n");
        b.append("    Typography: ").append(v.typography()).append("\n");
        b.append("    Composition: ").append(v.composition()).append("\n");
        if (v.avoid() != null && !v.avoid().isEmpty()) {
            b.append("    Avoid: ").append(String.join(", ", v.avoid())).append("\n");
        }
        return b.toString();
    }

    /** Appends the universal negative prompt + IP rule when configured (guarded for unit tests). */
    private void appendUniversalRules(StringBuilder sb) {
        UniversalRules rules = vibeLibrary.universalRules();
        if (rules == null) return;
        if (rules.negativePrompt() != null && !rules.negativePrompt().isBlank()) {
            sb.append("AVOID in the artwork: ")
                    .append(rules.negativePrompt())
                    .append(", ")
                    .append(GENERIC_POSTER_NEGATIVE_PROMPT)
                    .append("\n");
        } else {
            sb.append("AVOID in the artwork: ").append(GENERIC_POSTER_NEGATIVE_PROMPT).append("\n");
        }
        if (rules.ipRule() != null && !rules.ipRule().isBlank()) {
            sb.append("IP rule: ").append(rules.ipRule()).append("\n");
        }
        sb.append("\n");
    }
}
