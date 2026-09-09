package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.StyleCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PosterStyleValidationService {
    private static final Logger log = LoggerFactory.getLogger(PosterStyleValidationService.class);

    private final PosterStyleValidationClient client;
    private final boolean enabled;

    public PosterStyleValidationService(
            PosterStyleValidationClient client,
            @Value("${poster.style-validation.enabled:true}") boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    public ValidationDecision validateOrExplain(byte[] imageBytes, StyleCard card, HeroType heroType) {
        if (!enabled || card == null) {
            return new ValidationDecision(true, null);
        }

        PosterStyleValidationClient.StyleValidationResult result;
        try {
            result = client.validate(imageBytes, card, heroType);
        } catch (RuntimeException e) {
            // This gate is SOFT: the orchestrator ships a non-accepted style decision best-effort
            // without spending another render. An unusable verdict (malformed/truncated LLM JSON,
            // a 5xx, a transport error) must therefore degrade to a non-acceptance, never escape —
            // an escaping exception failed every text-accepted variant and, because all three
            // variants share this gate, threw away three paid renders as a 502.
            log.warn("Style gate could not be evaluated; accepting best-effort: {}", e.toString());
            return new ValidationDecision(false,
                    "style validation could not be evaluated: " + e.getMessage());
        }
        if (result.accepted()) {
            return new ValidationDecision(true, null);
        }

        return new ValidationDecision(false, buildReason(result));
    }

    private static String buildReason(PosterStyleValidationClient.StyleValidationResult result) {
        List<String> parts = new ArrayList<>();
        if (!result.heroSubjectPresent()) {
            parts.add("declared hero subject not present");
        }
        if (!result.mediumMatches()) {
            parts.add("medium does not match the style card");
        }
        if (!result.paletteMatches()) {
            parts.add("palette does not match the style card");
        }
        if (result.reasons() != null) {
            for (String reason : result.reasons()) {
                if (reason != null && !reason.isBlank()) {
                    parts.add(reason.trim());
                }
            }
        }
        if (parts.isEmpty()) {
            parts.add("poster rejected by style-adherence gate");
        }
        return String.join("; ", parts);
    }

    public record ValidationDecision(boolean accepted, String reason) {}
}
