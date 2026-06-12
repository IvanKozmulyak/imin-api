package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.PosterTextSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PosterTextValidationService {
    private static final Logger log = LoggerFactory.getLogger(PosterTextValidationService.class);

    private final PosterTextValidationClient client;
    private final boolean enabled;
    private final int maxExtraTextItems;

    public PosterTextValidationService(
            PosterTextValidationClient client,
            @Value("${poster.text-validation.enabled:true}") boolean enabled,
            @Value("${poster.text-validation.max-extra-text-items:0}") int maxExtraTextItems) {
        this.client = client;
        this.enabled = enabled;
        this.maxExtraTextItems = Math.max(0, maxExtraTextItems);
    }

    public ValidationDecision validateOrExplain(byte[] imageBytes, PosterTextSpec spec) {
        // Run the gate whenever there is any text contract to check. Previously it also short-circuited
        // when `required` was empty, which let invented text ship on posters that only had optional text.
        if (!enabled || (spec.required().isEmpty() && spec.allowed().isEmpty())) {
            return ValidationDecision.pass();
        }

        PosterTextValidationClient.ValidationResult result;
        try {
            result = client.validate(imageBytes, spec);
        } catch (RuntimeException e) {
            // A flaky/unusable verdict (truncated or malformed LLM JSON, transport error) must NOT abort
            // the variant. Treat it as a non-acceptance so the orchestrator runs its corrective remix and,
            // if the gate keeps failing, ships best-effort — the same path as any other gate rejection.
            // Never fail-open: an unevaluated gate must not let invented text through.
            log.warn("Text gate could not be evaluated; treating as not-accepted: {}", e.toString());
            return new ValidationDecision(false,
                    "text validation could not be evaluated: " + e.getMessage(),
                    List.of(), List.of());
        }
        List<String> missing = result.missingRequired() == null ? List.of() : result.missingRequired();
        List<String> extra = result.extraText() == null ? List.of() : result.extraText();

        // Enforce the contract server-side — do NOT trust the model's `accepted` flag alone.
        // A false-negative `accepted:true` over invented text is exactly how hallucinations shipped.
        boolean ok = result.accepted() && missing.isEmpty() && extra.size() <= maxExtraTextItems;
        if (ok) {
            return ValidationDecision.pass();
        }

        return new ValidationDecision(false,
                "missing required text: " + missing + "; extra text: " + extra,
                missing, extra);
    }

    public record ValidationDecision(
            boolean accepted, String reason,
            java.util.List<String> missingRequired, java.util.List<String> extraText) {
        public static ValidationDecision pass() {
            return new ValidationDecision(true, null, java.util.List.of(), java.util.List.of());
        }
    }
}
