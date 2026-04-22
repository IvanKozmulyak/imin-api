package com.imin.iminapi.service.poster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class IdeogramClient {

    private static final Logger log = LoggerFactory.getLogger(IdeogramClient.class);

    private final ReplicateClient replicateClient;
    private final String turboModel;
    private final String qualityModel;

    public IdeogramClient(
            ReplicateClient replicateClient,
            @Value("${replicate.models.ideogram-turbo:ideogram-ai/ideogram-v3-turbo}") String turboModel,
            @Value("${replicate.models.ideogram-quality:ideogram-ai/ideogram-v3-quality}") String qualityModel) {
        this.replicateClient = replicateClient;
        this.turboModel = turboModel;
        this.qualityModel = qualityModel;
    }

    public record IdeogramResult(String imageUrl, long seed, Duration generationTime, String model) {}

    public IdeogramResult generate(
            String prompt,
            String aspectRatio,
            List<String> styleReferenceImages,
            long seed,
            String styleType) {
        return run(turboModel, prompt, aspectRatio, styleReferenceImages, seed, styleType);
    }

    public IdeogramResult generateWithQualityTier(
            String prompt,
            String aspectRatio,
            List<String> styleReferenceImages,
            long seed,
            String styleType) {
        return run(qualityModel, prompt, aspectRatio, styleReferenceImages, seed, styleType);
    }

    private IdeogramResult run(
            String model,
            String prompt,
            String aspectRatio,
            List<String> styleReferenceImages,
            long seed,
            String styleType) {
        boolean hasRefs = styleReferenceImages != null && !styleReferenceImages.isEmpty();
        // Ideogram V3 rejects any style_type other than "Auto"/"General" when style_reference_images is set.
        // Enum values are case-sensitive: None, Auto, General, Realistic, Design.
        String effectiveStyleType = hasRefs ? "Auto" : (styleType != null ? styleType : "Design");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", prompt);
        input.put("aspect_ratio", aspectRatio != null ? aspectRatio : "3:4");
        input.put("style_type", effectiveStyleType);
        input.put("magic_prompt_option", "Off");
        input.put("seed", seed);
        if (hasRefs) {
            input.put("style_reference_images", styleReferenceImages);
        }

        Instant start = Instant.now();
        String imageUrl = replicateClient.runAndAwaitImageUrl(model, input);
        Duration elapsed = Duration.between(start, Instant.now());
        log.info("Ideogram ({}) generated image in {} ms", model, elapsed.toMillis());
        return new IdeogramResult(imageUrl, seed, elapsed, model);
    }
}
