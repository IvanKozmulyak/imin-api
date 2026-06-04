package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.PosterTextSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PosterTextValidationService {
    private final PosterTextValidationClient client;
    private final boolean enabled;

    public PosterTextValidationService(
            PosterTextValidationClient client,
            @Value("${poster.text-validation.enabled:true}") boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    public ValidationDecision validateOrExplain(byte[] imageBytes, PosterTextSpec spec) {
        if (!enabled || spec.required().isEmpty()) {
            return new ValidationDecision(true, null);
        }

        PosterTextValidationClient.ValidationResult result = client.validate(imageBytes, spec);
        if (result.accepted()) {
            return new ValidationDecision(true, null);
        }

        return new ValidationDecision(false,
                "missing required text: " + result.missingRequired() + "; extra text: " + result.extraText());
    }

    public record ValidationDecision(boolean accepted, String reason) {}
}
