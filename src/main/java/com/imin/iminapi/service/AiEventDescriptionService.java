package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.service.poster.ReferenceImageLibrary;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AiEventDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(AiEventDescriptionService.class);

    private final ChatClient chatClient;
    private final ReferenceImageLibrary referenceLibrary;

    public static final Set<String> VALID_SUB_STYLE_TAGS = Set.of(
            "neon_underground",
            "chrome_tropical",
            "sunset_silhouette",
            "flat_graphic",
            "aquatic_distressed",
            "industrial_minimal",
            "golden_editorial"
    );

    private static final Set<String> VALID_VARIANT_STYLES = Set.of("atmospheric", "graphic", "minimal");
    private static final Set<String> VALID_ASPECTS = Set.of("3:4", "1:1", "4:5");
    private static final Pattern WORDS = Pattern.compile("\\s+");
    private static final int MIN_WORDS = 30;
    private static final int MAX_WORDS = 150;
    private static final int MAX_ATTEMPTS = 2;

    public PosterConcept generateConcept(EventCreatorRequest request) {
        String reinforcement = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            PosterConcept concept = chatClient.prompt()
                    .user(buildPrompt(request, reinforcement))
                    .call()
                    .entity(PosterConcept.class);
            String validationError = validate(concept);
            if (validationError == null) {
                log.debug("Concept generated on attempt {}: tag={}, variants={}",
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
        if (concept.subStyleTag() == null || !VALID_SUB_STYLE_TAGS.contains(concept.subStyleTag())) {
            return "sub_style_tag must be one of " + VALID_SUB_STYLE_TAGS;
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

    String buildPrompt(EventCreatorRequest request, String reinforcement) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an art director for a nightlife event poster. Your output drives ")
          .append("Ideogram V3, a text-in-image model (~90-95% accuracy on quoted strings).\n\n")
          .append("Return a JSON object with exactly these fields:\n");
        String pinned = request.subStyleTag();
        if (pinned != null && !pinned.isBlank()) {
            String descriptor = nonBlankOrPlaceholder(referenceLibrary.descriptor(pinned));
            sb.append("- sub_style_tag is pre-selected as ").append(pinned)
              .append(". Use the following style notes in every variant:\n    ")
              .append(descriptor).append("\n");
        } else {
            sb.append("- sub_style_tag: pick one and weave its style notes into every variant\n");
            for (String tag : referenceLibrary.tags()) {
                String descriptor = nonBlankOrPlaceholder(referenceLibrary.descriptor(tag));
                sb.append("    ").append(tag).append(" — ").append(descriptor).append("\n");
            }
        }
        sb.append("- color_palette_description: a brief human-readable description of the dominant colors\n")
          .append("- variants: exactly 3 objects, each with:\n")
          .append("    - variant_style: one of atmospheric, graphic, minimal\n")
          .append("    - ideogram_prompt: a COMPLETE self-contained prompt, 30-150 words, describing the scene and explicitly including every text element in DOUBLE QUOTES for literal rendering (Ideogram renders quoted strings as typography)\n")
          .append("    - aspect_ratio: one of 3:4, 1:1, 4:5\n")
          .append("    - style_type: always \"Design\"\n\n")
          .append("STRICT RULES for each ideogram_prompt:\n")
          .append("- Include a MAXIMUM of 7 text elements (event title, date, venue, DJ, city, ticket price/CTA, hashtag — only the ones given below)\n")
          .append("- Wrap every single text element in double quotes exactly as it should appear\n")
          .append("- Describe typography treatment explicitly (e.g. \"chrome 3D lettering\", \"distressed serif\", \"neon outline\")\n")
          .append("- Never name real venues, real brands, or real DJs by name other than the djName provided\n")
          .append("- End the prompt with: \"no other text elements\"\n\n")
          .append("Event brief:\n")
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

    private static String nonBlankOrPlaceholder(String s) {
        return (s == null || s.isBlank()) ? "(no descriptor available)" : s;
    }
}
