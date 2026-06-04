package com.imin.iminapi.service.poster.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.service.poster.ReferenceImageLibrary;
import com.imin.iminapi.service.poster.VibeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time authoring tool that drafts a per-vibe {@code StyleCard} YAML from that vibe's real
 * reference posters, using a multimodal LLM (OpenRouter, vision-capable model).
 *
 * <p><b>How to run.</b> This is a {@code @Profile("tools")} {@link CommandLineRunner}, so it NEVER
 * runs in dev/prod/test — only when the app is started with the {@code tools} profile:
 * <pre>{@code
 *   OPENROUTER_API_KEY=... ./mvnw spring-boot:run \
 *       -Dspring-boot.run.profiles=tools
 *   # or for a packaged jar:
 *   java -jar app.jar --spring.profiles.active=tools
 * }</pre>
 * It writes one {@code {vibeId}.yaml} per vibe to {@code poster.style-cards.generator-out-dir}
 * (default {@code ./target/style-cards}). <b>Hand-review every generated YAML</b> — the LLM is
 * asked for concrete visual nouns that actually appear in the references and to avoid real brand /
 * artist names, but that must be verified — then commit the reviewed cards under
 * {@code src/main/resources/vibes/style-cards/}.
 *
 * <p><b>The runtime never invokes this.</b> At runtime, committed cards are loaded by
 * {@code StyleCardLibrary}; this generator only exists to (re)author them offline.
 */
@Profile("tools")
@Component
public class StyleCardGenerator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StyleCardGenerator.class);

    /**
     * The instruction that documents the expected YAML schema for a {@code StyleCard}. The pools
     * must name concrete visual nouns that actually appear in the attached references, with no real
     * brand or artist names.
     */
    private static final String INSTRUCTION = """
            You are a visual art director cataloguing a poster aesthetic from a small set of real
            reference flyers (attached below as images). Study ALL the attached images, then output a
            single YAML "style card" that captures the shared visual language across them.

            Output requirements:
            - Output YAML ONLY. No prose, no explanation, no markdown headings, no code fences.
            - Every pool entry must name CONCRETE VISUAL NOUNS that ACTUALLY APPEAR in the attached
              references (real objects, scenes, textures, layouts you can see). Do not invent things
              that are not present.
            - Never use real brand names, real artist names, real venue names, or any trademark.
              Describe what you see generically (e.g. "a vintage boombox", not a brand model).

            Emit exactly this schema (keys in this order):

            medium: <one of: photo | illustration | collage | mixed — the dominant medium>
            palette:            # 3-4 dominant colors, each an [r, g, b] triple, channels 0-255
              - [r, g, b]
              - [r, g, b]
              - [r, g, b]
            hero_subjects:
              people:           # 4-6 concrete, photographable human-hero scenes seen in the refs
                - <scene>
              object:           # 4-6 concrete, photographable NON-human hero objects/scenes
                - <scene>
            compositions:       # 5-8 layout archetypes: where the hero sits, where type goes, the crop
              - <layout>
            accents:            # 4-6 media/texture twists actually present (grain, flash, photocopy...)
              - <accent>
            palette_twists:     # human-readable palette variants to vary the prompt
              - <twist>
            type_treatments:    # typography treatments seen in the references
              - <treatment>
            example_prompts:    # 2-3 COMPLETE 60-180 word Recraft prompts in this exact aesthetic,
                                # each a single full prompt paragraph (no placeholders)
              - <full prompt>

            Return the YAML now.
            """;

    private final VibeLibrary vibeLibrary;
    private final ReferenceImageLibrary referenceLibrary;
    private final RestClient restClient;
    private final String model;
    private final Path outDir;

    public StyleCardGenerator(
            VibeLibrary vibeLibrary,
            ReferenceImageLibrary referenceLibrary,
            @Value("${openrouter.api-key}") String apiKey,
            @Value("${openrouter.base-url:https://openrouter.ai/api}") String baseUrl,
            @Value("${poster.style-cards.generator-model:${openrouter.model:openai/gpt-4o-mini}}") String model,
            @Value("${poster.style-cards.generator-out-dir:./target/style-cards}") String outDir) {
        this.vibeLibrary = vibeLibrary;
        this.referenceLibrary = referenceLibrary;
        this.model = model;
        this.outDir = Path.of(outDir);
        this.restClient = RestClient.builder()
                .baseUrl(normalizeOpenRouterV1BaseUrl(baseUrl))
                .requestInterceptor((request, body, execution) -> {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException(
                                "OPENROUTER_API_KEY is not configured. Set it before running the style-card generator.");
                    }
                    request.getHeaders().setBearerAuth(apiKey);
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public void run(String... args) throws Exception {
        List<Vibe> vibes = vibeLibrary.all();
        Files.createDirectories(outDir);
        log.info("[style-card-gen] starting: {} vibes, model={}, outDir={}", vibes.size(), model, outDir.toAbsolutePath());

        int ok = 0;
        int skipped = 0;
        int failed = 0;
        for (Vibe vibe : vibes) {
            String vibeId = vibe.id();
            try {
                List<byte[]> refs = referenceLibrary.loadAllBytes(vibeId);
                if (refs == null || refs.isEmpty()) {
                    log.warn("[style-card-gen] vibe '{}' has no references — skipping", vibeId);
                    skipped++;
                    continue;
                }
                log.info("[style-card-gen] vibe '{}': {} references — calling model", vibeId, refs.size());
                String yaml = generateYaml(refs);
                Path out = outDir.resolve(vibeId + ".yaml");
                Files.writeString(out, yaml);
                log.info("[style-card-gen] vibe '{}': wrote {}", vibeId, out.toAbsolutePath());
                ok++;
            } catch (Exception e) {
                // Wrap each vibe so one failure doesn't abort the batch.
                log.error("[style-card-gen] vibe '{}' failed: {}", vibeId, e.getMessage(), e);
                failed++;
            }
        }
        log.info("[style-card-gen] done: {} written, {} skipped, {} failed. Hand-review the YAML in {}, "
                        + "then commit reviewed cards under src/main/resources/vibes/style-cards/.",
                ok, skipped, failed, outDir.toAbsolutePath());
    }

    private String generateYaml(List<byte[]> referenceBytes) {
        List<Map<String, Object>> content = new ArrayList<>();

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", INSTRUCTION);
        content.add(textPart);

        for (byte[] bytes : referenceBytes) {
            content.add(imagePart(bytes));
        }

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.4);
        // No response_format=json_object: we want raw YAML text back, not JSON.
        body.put("messages", List.of(userMessage));

        ChatCompletionResponse response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ChatCompletionResponse.class);

        return stripCodeFences(extractContent(response));
    }

    private static Map<String, Object> imagePart(byte[] bytes) {
        String dataUri = "data:" + sniffMime(bytes) + ";base64," + Base64.getEncoder().encodeToString(bytes);
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", dataUri);
        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imageUrl);
        return imagePart;
    }

    private static String extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("OpenRouter style-card response contained no choices");
        }
        Choice choice = response.choices().get(0);
        if (choice.message() == null || choice.message().content() == null || choice.message().content().isBlank()) {
            throw new IllegalStateException("OpenRouter style-card response contained no message content");
        }
        return choice.message().content().trim();
    }

    /**
     * Sniff the image mime type from the leading magic bytes (jpg / png / webp), defaulting to
     * {@code image/png}. Used to build the {@code data:} URI for each reference image part.
     */
    static String sniffMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        // WEBP: "RIFF" .... "WEBP"
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return "image/png";
    }

    /**
     * Remove a single Markdown code fence wrapping the content: a leading {@code ```yaml} /
     * {@code ```yml} / {@code ```} line and a trailing {@code ```} line. Unfenced content passes
     * through unchanged (trimmed).
     */
    static String stripCodeFences(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline < 0) {
            // Single line that's only a fence — nothing left.
            return "";
        }
        // Drop the opening fence line (```/```yaml/```yml...).
        String body = s.substring(firstNewline + 1);
        int lastFence = body.lastIndexOf("```");
        if (lastFence >= 0) {
            body = body.substring(0, lastFence);
        }
        return body.strip();
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content) {}
}
