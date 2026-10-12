package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenRouterPosterTextValidationClient implements PosterTextValidationClient {
    private static final Logger log = LoggerFactory.getLogger(OpenRouterPosterTextValidationClient.class);

    private static final String READ_PROMPT = """
            You are transcribing every piece of text on an event poster exactly as it is drawn. You do not check spelling or meaning.
            - Copy each piece of text character for character, keeping the exact upper- and lower-case of every letter. Do not correct spelling, change case, or complete cut-off words.
            - One entry per text block; join the lines of one block with a single space, in reading order.
            - "count" is how many separate places that exact text appears, including faint, ghosted, repeated, mirrored or background-pattern copies.
            - Include small, faint, rotated, vertical and marginal text: fine print, credit lines, captions, and logos or watermarks with letters.
            - Letter-like marks you cannot read go in "unreadable", once per area, as a short description with its position (e.g. "fine print, bottom-left"). A pattern with no letter-like shapes is not text.
            - At most 40 entries. Ignore QR codes.
            Return JSON only: {"texts":[{"text":"...","count":1}],"unreadable":[]}
            """;

    private static final String EDGE_PROMPT = """
            The images are four strips cut from the edges of one event poster, in this order: top, bottom, left, right. Side strips may hold rotated or vertical text.
            For each strip, transcribe every letter, digit or letter-like mark exactly as drawn (case kept, no corrections); text cut off by the strip edge counts. Marks you cannot read go in "unreadable" as a short description. A pattern with no letter-like shapes is not text.
            Return JSON only: {"top":{"texts":[],"unreadable":[]},"bottom":{"texts":[],"unreadable":[]},"left":{"texts":[],"unreadable":[]},"right":{"texts":[],"unreadable":[]}}
            """;

    private static final int MAX_COUNT = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String model;
    private final int maxTokens;

    public OpenRouterPosterTextValidationClient(
            @Value("${openrouter.api-key}") String apiKey,
            @Value("${openrouter.base-url:https://openrouter.ai/api}") String baseUrl,
            @Value("${poster.text-validation.model:openai/gpt-4o}") String model,
            @Value("${poster.text-validation.max-tokens:1500}") int maxTokens,
            @Value("${poster.validation.read-timeout-seconds:60}") long readTimeoutSeconds) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.restClient = com.imin.iminapi.config.OpenRouterRestClients.v1(
                baseUrl, apiKey, "enabling poster text validation", java.time.Duration.ofSeconds(readTimeoutSeconds));
    }

    @Override
    public Transcript read(byte[] png) {
        return parseTranscript(complete(READ_PROMPT, List.of(png)));
    }

    @Override
    public List<Transcript> readEdges(List<PosterEdgeBands.Band> bands) {
        return parseEdges(complete(EDGE_PROMPT, bands.stream().map(PosterEdgeBands.Band::png).toList()));
    }

    private String complete(String prompt, List<byte[]> images) {
        List<Object> content = new ArrayList<>();
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        content.add(textPart);
        for (byte[] png : images) {
            Map<String, Object> imageUrl = new LinkedHashMap<>();
            imageUrl.put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            Map<String, Object> imagePart = new LinkedHashMap<>();
            imagePart.put("type", "image_url");
            imagePart.put("image_url", imageUrl);
            content.add(imagePart);
        }

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        // Restrict routing to providers that do not store or train on what we send.
        // This request carries a rendered poster, which for a DJ-mode generation
        // contains a real person's likeness. See OpenRouterPrivacy.
        body.put(com.imin.iminapi.config.OpenRouterPrivacy.PROVIDER_FIELD,
                com.imin.iminapi.config.OpenRouterPrivacy.providerPolicy());
        body.put("temperature", 0);
        // Bounds a runaway transcription (texture-heavy posters can make the model enumerate garbled
        // "text" until the JSON truncates); a truncated reply is unusable, never "no text".
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
        return extractContent(response);
    }

    private static String extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new UnusableReadException("OpenRouter poster text read contained no choices");
        }
        Choice choice = response.choices().get(0);
        if (choice.message() == null || choice.message().content() == null || choice.message().content().isBlank()) {
            throw new UnusableReadException("OpenRouter poster text read contained no message content");
        }
        return choice.message().content().trim();
    }

    static Transcript parseTranscript(String content) {
        return toTranscript(readTree(content), "transcript");
    }

    /** Top, bottom, left, right; a reply missing any strip is unusable. */
    static List<Transcript> parseEdges(String content) {
        JsonNode root = readTree(content);
        List<Transcript> out = new ArrayList<>();
        for (String band : PosterEdgeBands.BAND_NAMES) {
            out.add(toTranscript(root.path(band), band));
        }
        return out;
    }

    private static JsonNode readTree(String content) {
        try {
            JsonNode root = MAPPER.readTree(content);
            if (root == null || !root.isObject()) {
                throw new UnusableReadException("OpenRouter poster text read was not a JSON object");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new UnusableReadException("OpenRouter poster text read returned malformed JSON", e);
        }
    }

    private static Transcript toTranscript(JsonNode node, String what) {
        if (!node.path("texts").isArray() || !node.path("unreadable").isArray()) {
            throw new UnusableReadException(
                    "OpenRouter poster text read (" + what + ") lacks the texts and unreadable arrays");
        }
        List<Seen> texts = new ArrayList<>();
        for (JsonNode entry : node.path("texts")) {
            String text = entry.isTextual() ? entry.asText() : entry.path("text").asText("");
            if (text.isBlank()) continue;
            JsonNode count = entry.path("count");
            int n = count.canConvertToInt() && count.asInt() >= 1 ? Math.min(count.asInt(), MAX_COUNT) : 1;
            texts.add(new Seen(text, n));
        }
        List<String> unreadable = new ArrayList<>();
        for (JsonNode entry : node.path("unreadable")) {
            if (entry.isTextual() && !entry.asText().isBlank()) unreadable.add(entry.asText().trim());
        }
        return new Transcript(List.copyOf(texts), List.copyOf(unreadable));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content) {}
}
