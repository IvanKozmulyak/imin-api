package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI image-generation client (alternative to {@link IdeogramClient}).
 *
 * Uses {@code /v1/images/generations} when no references are present, and
 * {@code /v1/images/edits} (multipart, gpt-image-1 semantics) when references
 * are supplied. Returns materialized PNG bytes (decoded from b64_json).
 *
 * OpenAI image API does not accept a seed parameter, so the seed passed in is
 * only used for log correlation.
 */
@Component
public class OpenAiImageClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageClient.class);

    private final RestClient openAiImageRestClient;
    private final String model;

    public OpenAiImageClient(
            RestClient openAiImageRestClient,
            @Value("${openai.image.model:gpt-image-1}") String model) {
        this.openAiImageRestClient = openAiImageRestClient;
        this.model = model;
    }

    public record OpenAiImageResult(byte[] imageBytes, Duration generationTime, String model) {}

    public OpenAiImageResult generate(
            String prompt,
            String aspectRatio,
            List<byte[]> referenceImages,
            long seed) {
        String size = mapAspectRatio(aspectRatio);
        boolean hasRefs = referenceImages != null && !referenceImages.isEmpty();

        Instant start = Instant.now();
        byte[] bytes = hasRefs
                ? callEdits(prompt, size, referenceImages)
                : callGenerations(prompt, size);
        Duration elapsed = Duration.between(start, Instant.now());
        log.info("OpenAI image ({}) generated in {} ms (size={}, refs={}, seed={})",
                model, elapsed.toMillis(), size, hasRefs ? referenceImages.size() : 0, seed);
        return new OpenAiImageResult(bytes, elapsed, model);
    }

    private byte[] callGenerations(String prompt, String size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("size", size);
        body.put("n", 1);

        ImagesResponse resp = openAiImageRestClient.post()
                .uri("/v1/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ImagesResponse.class);
        return decodeSingle(resp);
    }

    private byte[] callEdits(String prompt, String size, List<byte[]> referenceImages) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("model", model);
        parts.add("prompt", prompt);
        parts.add("size", size);
        parts.add("n", "1");
        // OpenAI rejects multiple parts named "image" with a duplicate_parameter error;
        // the array syntax "image[]" is required when sending more than one reference.
        boolean single = referenceImages.size() == 1;
        int i = 0;
        for (byte[] bytes : referenceImages) {
            final String filename = "ref_" + (i++) + ".png";
            parts.add(single ? "image" : "image[]", new ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename; }
            });
        }

        ImagesResponse resp = openAiImageRestClient.post()
                .uri("/v1/images/edits")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(ImagesResponse.class);
        return decodeSingle(resp);
    }

    private byte[] decodeSingle(ImagesResponse resp) {
        if (resp == null || resp.data() == null || resp.data().isEmpty()) {
            throw new IllegalStateException("OpenAI image response contained no data");
        }
        ImageDatum first = resp.data().get(0);
        if (first.b64_json() == null || first.b64_json().isBlank()) {
            throw new IllegalStateException(
                    "OpenAI image response missing b64_json (url-only responses are not supported)");
        }
        return Base64.getDecoder().decode(first.b64_json());
    }

    static String mapAspectRatio(String aspectRatio) {
        if (aspectRatio == null) return "1024x1536";
        return switch (aspectRatio) {
            case "1:1" -> "1024x1024";
            case "3:2", "16:9" -> "1536x1024";
            // 3:4 and 4:5 both fall back to portrait — OpenAI doesn't offer 4:5 natively.
            default -> "1024x1536";
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImagesResponse(List<ImageDatum> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImageDatum(String b64_json, String url) {}
}
