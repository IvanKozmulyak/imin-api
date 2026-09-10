package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.PosterTextSpec;
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
public class OpenRouterPosterTextValidationClient implements PosterTextValidationClient {
    private static final Logger log = LoggerFactory.getLogger(OpenRouterPosterTextValidationClient.class);

    private static final String VALIDATION_PROMPT = """
            You are an OCR-and-verification checker for an event poster. Work in three steps.

            STEP 1 — Transcribe: read and list EVERY piece of text visible in the image,
            including small, faint, stylized, rotated, or decorative text. Do not skip anything.

            STEP 2 — Classify each transcribed string against the lists below:
              - matches a Required line (exactly or near-exactly) -> required
              - matches an Allowed line -> allowed
              - anything else -> invented. "invented" covers misspelled or garbled words,
                nonsense or fake letterforms, pseudo-text, lorem ipsum, duplicated/repeated
                text, logos, watermarks, and any words not present in the lists.

            STEP 3 — Report:
              - missingRequired: every Required line that does NOT appear in the image.
              - extraText: the DISTINCT invented strings you found (most prominent first), deduplicated.
                Do not repeat the same string, and list at most 25 — once you have captured the clearly
                invented text, stop. A dense decorative pattern is not text; do not transcribe it letter
                by letter.
              - accepted: true ONLY IF missingRequired is empty AND extraText is empty.

            Ignore QR codes. Be strict: when unsure whether a string is real or invented,
            put it in extraText. Return JSON only.
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxTokens;

    public OpenRouterPosterTextValidationClient(
            @Value("${openrouter.api-key}") String apiKey,
            @Value("${openrouter.base-url:https://openrouter.ai/api}") String baseUrl,
            @Value("${poster.text-validation.model:openai/gpt-4o}") String model,
            @Value("${poster.text-validation.max-tokens:1500}") int maxTokens) {
        this.objectMapper = new ObjectMapper();
        this.model = model;
        this.maxTokens = maxTokens;
        this.restClient = com.imin.iminapi.config.OpenRouterRestClients.v1(
                baseUrl, apiKey, "enabling poster text validation");
    }

    @Override
    public ValidationResult validate(byte[] imageBytes, PosterTextSpec spec) {
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes);

        String promptText = validationPrompt(spec);

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
        // Restrict routing to providers that do not store or train on what we send.
        // This request carries a rendered poster, which for a DJ-mode generation
        // contains a real person's likeness. See OpenRouterPrivacy.
        body.put(com.imin.iminapi.config.OpenRouterPrivacy.PROVIDER_FIELD,
                com.imin.iminapi.config.OpenRouterPrivacy.providerPolicy());
        body.put("temperature", 0);
        // Bound the response so a runaway OCR transcription (texture-heavy posters can make the model
        // enumerate garbled "text" until it overruns and truncates the JSON mid-string) can't produce an
        // unparseable verdict. A real verdict is a handful of short strings, far under this cap.
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
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

    private String validationPrompt(PosterTextSpec spec) {
        return VALIDATION_PROMPT + "\n"
                + "Required text:\n" + bulletLines(spec.required()) + "\n\n"
                + "Allowed text:\n" + bulletLines(spec.allowed()) + "\n\n"
                + "List every invented string you find in extraText.\n"
                + "Expected JSON shape:\n"
                + "{\"accepted\":true,\"missingRequired\":[],\"extraText\":[]}";
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
            throw new IllegalStateException("OpenRouter poster text validation response contained no choices");
        }
        Choice choice = response.choices().get(0);
        if (choice.message() == null || choice.message().content() == null || choice.message().content().isBlank()) {
            throw new IllegalStateException("OpenRouter poster text validation response contained no message content");
        }
        return choice.message().content().trim();
    }

    private ValidationResult parseValidationResult(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (!root.path("accepted").isBoolean()
                    || !root.path("missingRequired").isArray()
                    || !root.path("extraText").isArray()) {
                throw new IllegalStateException(
                        "OpenRouter poster text validation returned malformed JSON: expected accepted boolean, missingRequired array, and extraText array");
            }
            return objectMapper.treeToValue(root, ValidationResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OpenRouter poster text validation returned malformed JSON", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content) {}
}
