package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.Rgb;
import com.imin.iminapi.dto.StyleCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenRouterPosterStyleValidationClient implements PosterStyleValidationClient {
    private static final Logger log = LoggerFactory.getLogger(OpenRouterPosterStyleValidationClient.class);

    private static final String VALIDATION_PROMPT = """
            You are a strict art director checking whether a rendered event poster matches its intended
            visual style. Judge the attached poster image against the style brief below.
            Reject (accepted=false) if the declared pictorial hero is absent (for people/object heroes),
            or if the rendered medium is clearly wrong (e.g. a flat illustration where a photo was required).
            Ignore the QR code and any small address band. Return JSON only.
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public OpenRouterPosterStyleValidationClient(
            @Value("${openrouter.api-key}") String apiKey,
            @Value("${openrouter.base-url:https://openrouter.ai/api}") String baseUrl,
            @Value("${poster.style-validation.model:openai/gpt-4o-mini}") String model) {
        this.objectMapper = new ObjectMapper();
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(normalizeOpenRouterV1BaseUrl(baseUrl))
                .requestInterceptor((request, body, execution) -> {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException(
                                "OPENROUTER_API_KEY is not configured. Set it before enabling poster style validation.");
                    }
                    request.getHeaders().setBearerAuth(apiKey);
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public StyleValidationResult validate(byte[] imageBytes, StyleCard card, HeroType declaredHeroType) {
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes);

        String promptText = validationPrompt(card, declaredHeroType);
        log.info("[LLM style-validation] prompt sent (model={}):\n{}", model, promptText);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", promptText);

        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", dataUri);
        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imageUrl);

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", List.of(textPart, imagePart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(userMessage));

        ChatCompletionResponse response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ChatCompletionResponse.class);

        return parseValidationResult(extractContent(response));
    }

    static String normalizeOpenRouterV1BaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalStateException("openrouter.base-url is not configured");
        }
        if (normalized.endsWith("/v1")) {
            return normalized;
        }
        return normalized + "/v1";
    }

    private String validationPrompt(StyleCard card, HeroType declaredHeroType) {
        String wire = declaredHeroType.wire();
        StringBuilder sb = new StringBuilder();
        sb.append(VALIDATION_PROMPT).append("\n");
        sb.append("Declared hero type for this variant: ").append(wire).append("\n");
        sb.append("Expected medium: ").append(mediumOf(card)).append("\n");
        sb.append("Expected palette: ").append(paletteDescription(card)).append("\n");

        if (declaredHeroType == HeroType.TYPOGRAPHIC || declaredHeroType == HeroType.ABSTRACT_GRAPHIC) {
            sb.append("This poster has NO discrete pictorial hero subject (typography or an abstract ")
                    .append("graphic field IS the hero). Therefore report \"heroSubjectPresent\":true and ")
                    .append("judge only the medium and palette.\n");
        } else {
            sb.append("Expected hero subject — the poster should clearly feature one of these scenes:\n")
                    .append(bulletLines(card.heroSubjectsFor(declaredHeroType)))
                    .append("\nIf no such pictorial hero is present, report \"heroSubjectPresent\":false and reject.\n");
        }

        sb.append("\nReturn JSON EXACTLY in this shape:\n");
        sb.append("{\"accepted\":true,\"heroSubjectPresent\":true,\"mediumMatches\":true,")
                .append("\"paletteMatches\":true,\"reasons\":[]}");
        return sb.toString();
    }

    private static String mediumOf(StyleCard card) {
        String medium = card == null ? null : card.medium();
        return (medium == null || medium.isBlank()) ? "unspecified" : medium;
    }

    private static String paletteDescription(StyleCard card) {
        List<Rgb> palette = card == null ? null : card.palette();
        if (palette == null || palette.isEmpty()) {
            return "unspecified";
        }
        return palette.stream()
                .map(rgb -> "rgb(" + rgb.r() + "," + rgb.g() + "," + rgb.b() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("unspecified");
    }

    private static String bulletLines(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "- none";
        }
        return values.stream()
                .map(value -> "- " + value)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("- none");
    }

    private String extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("OpenRouter poster style validation response contained no choices");
        }
        Choice choice = response.choices().get(0);
        if (choice.message() == null || choice.message().content() == null || choice.message().content().isBlank()) {
            throw new IllegalStateException("OpenRouter poster style validation response contained no message content");
        }
        return choice.message().content().trim();
    }

    private StyleValidationResult parseValidationResult(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (!root.path("accepted").isBoolean()
                    || !root.path("heroSubjectPresent").isBoolean()
                    || !root.path("mediumMatches").isBoolean()
                    || !root.path("paletteMatches").isBoolean()
                    || !root.path("reasons").isArray()) {
                throw new IllegalStateException(
                        "OpenRouter poster style validation returned malformed JSON: expected accepted, "
                                + "heroSubjectPresent, mediumMatches, paletteMatches booleans and reasons array");
            }
            return objectMapper.treeToValue(root, StyleValidationResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OpenRouter poster style validation returned malformed JSON", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content) {}
}
