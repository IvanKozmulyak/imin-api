package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.StyleReferencePart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Native Ideogram V3 client (direct API, not Replicate). Auth is the {@code Api-Key} header
 * (see {@link com.imin.iminapi.config.IdeogramImageConfig}). The art director already wrote the
 * prompt, so {@code magic_prompt=OFF}. Ideogram V3 accepts EXACTLY ONE style control, so curated
 * reference images win when present and the per-vibe {@code style_preset} is the no-refs fallback.
 * The brand palette is a SEPARATE control: {@code color_palette} combines with either style control
 * (only {@code style_codes} conflicts with reference images per the API docs), so brand colours are
 * enforced structurally even when reference images carry a different palette.
 * The response carries a temporary image URL, which is downloaded immediately (the link expires).
 *
 * <p>The initial {@code generate} renders at the QUALITY tier; the corrective {@code remix} (which
 * only fixes baked text on a render we already liked) renders at the cheaper/faster TURBO tier.
 *
 * <p>When a non-null {@code characterRef} is supplied (DJ mode), the character reference image is
 * attached and the three gated params — colour palette, seed, and style control — are each emitted
 * only when their corresponding probe-determined flag is {@code true} (defaults: all false).
 */
@Component
public class IdeogramV3Client {

    private static final Logger log = LoggerFactory.getLogger(IdeogramV3Client.class);

    static final String GENERATE_PATH = "/v1/ideogram-v3/generate";
    static final String REMIX_PATH = "/v1/ideogram-v3/remix";
    static final String ASPECT_RATIO = "4x5";   // native uses NxM
    static final String MAGIC_PROMPT = "OFF";

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final double MIN_COLOR_WEIGHT = 0.05; // API floor
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient ideogramRestClient;
    private final String generateSpeed;
    private final String remixSpeed;
    private final boolean copyrightDetection;
    private final boolean characterColorPalette;
    private final boolean characterSeed;
    private final boolean characterStyleControl;

    public IdeogramV3Client(
            RestClient ideogramRestClient,
            @Value("${ideogram.generate.rendering-speed:QUALITY}") String generateSpeed,
            @Value("${ideogram.remix.rendering-speed:TURBO}") String remixSpeed,
            @Value("${ideogram.copyright-detection:true}") boolean copyrightDetection,
            @Value("${ideogram.character.color-palette:false}") boolean characterColorPalette,
            @Value("${ideogram.character.seed:false}") boolean characterSeed,
            @Value("${ideogram.character.style-control:false}") boolean characterStyleControl) {
        this.ideogramRestClient = ideogramRestClient;
        this.generateSpeed = (generateSpeed == null || generateSpeed.isBlank()) ? "QUALITY" : generateSpeed;
        this.remixSpeed = (remixSpeed == null || remixSpeed.isBlank()) ? "TURBO" : remixSpeed;
        this.copyrightDetection = copyrightDetection;
        this.characterColorPalette = characterColorPalette;
        this.characterSeed = characterSeed;
        this.characterStyleControl = characterStyleControl;
    }

    public record IdeogramResult(byte[] imageBytes, long seed) {}

    /** Text-to-image generate (QUALITY tier). {@code characterRef} non-null switches DJ mode. */
    public IdeogramResult generate(String prompt, long seed, List<StyleReferencePart> styleRefs,
                                   String stylePreset, List<String> paletteHexes,
                                   StyleReferencePart characterRef) {
        MultiValueMap<String, Object> parts =
                buildGenerateParts(prompt, seed, styleRefs, stylePreset, paletteHexes, characterRef);
        log.info("[ideogram v3 generate] promptLen={} speed={} {} palette={} seed={} charRef={}",
                prompt.length(), generateSpeed, styleLabel(styleRefs, stylePreset),
                paletteHexes == null ? 0 : paletteHexes.size(), seed, characterRef != null);
        return new IdeogramResult(post(GENERATE_PATH, parts), seed);
    }

    MultiValueMap<String, Object> buildGenerateParts(String prompt, long seed,
            List<StyleReferencePart> styleRefs, String stylePreset, List<String> paletteHexes,
            StyleReferencePart characterRef) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("prompt", prompt);
        parts.add("aspect_ratio", ASPECT_RATIO);
        parts.add("rendering_speed", generateSpeed);
        parts.add("magic_prompt", MAGIC_PROMPT);
        parts.add("enable_copyright_detection", String.valueOf(copyrightDetection));
        applyConditional(parts, seed, styleRefs, stylePreset, paletteHexes, characterRef);
        return parts;
    }

    /** Image-conditioned remix (TURBO tier). {@code characterRef} keeps the DJ likeness through corrections. */
    public IdeogramResult remix(byte[] image, String prompt, int imageWeight, long seed,
                                List<StyleReferencePart> styleRefs, String stylePreset,
                                List<String> paletteHexes, StyleReferencePart characterRef) {
        MultiValueMap<String, Object> parts =
                buildRemixParts(image, prompt, imageWeight, seed, styleRefs, stylePreset, paletteHexes, characterRef);
        log.info("[ideogram v3 remix] promptLen={} weight={} {} palette={} seed={} charRef={}",
                prompt.length(), imageWeight, styleLabel(styleRefs, stylePreset),
                paletteHexes == null ? 0 : paletteHexes.size(), seed, characterRef != null);
        return new IdeogramResult(post(REMIX_PATH, parts), seed);
    }

    MultiValueMap<String, Object> buildRemixParts(byte[] image, String prompt, int imageWeight, long seed,
            List<StyleReferencePart> styleRefs, String stylePreset, List<String> paletteHexes,
            StyleReferencePart characterRef) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("image", filePart(new StyleReferencePart(image, "source.png", "image/png")));
        parts.add("prompt", prompt);
        parts.add("image_weight", String.valueOf(imageWeight));
        parts.add("aspect_ratio", ASPECT_RATIO);
        parts.add("rendering_speed", remixSpeed);
        parts.add("magic_prompt", MAGIC_PROMPT);
        applyConditional(parts, seed, styleRefs, stylePreset, paletteHexes, characterRef);
        return parts;
    }

    /**
     * The DJ-mode switch. Ideogram's product docs say Color Palette / Seed / Style Reference are
     * unavailable with a character reference; the API reference doesn't confirm it, so each param
     * is gated behind a probe-determined flag (see the 2026-06-12 probe results doc). Without a
     * character ref this emits the exact baseline params.
     */
    private void applyConditional(MultiValueMap<String, Object> parts, long seed,
            List<StyleReferencePart> styleRefs, String stylePreset, List<String> paletteHexes,
            StyleReferencePart characterRef) {
        if (characterRef == null) {
            parts.add("seed", String.valueOf(seed));
            applyStyleControl(parts, styleRefs, stylePreset);
            applyColorPalette(parts, paletteHexes);
            return;
        }
        parts.add("character_reference_images", filePart(characterRef));
        if (characterSeed) parts.add("seed", String.valueOf(seed));
        if (characterStyleControl) applyStyleControl(parts, styleRefs, stylePreset);
        if (characterColorPalette) applyColorPalette(parts, paletteHexes);
    }

    /** Apply exactly one style control: reference images win; style_preset is the no-refs fallback. */
    private void applyStyleControl(MultiValueMap<String, Object> parts,
                                   List<StyleReferencePart> styleRefs, String stylePreset) {
        boolean hasRefs = styleRefs != null && !styleRefs.isEmpty();
        if (hasRefs) {
            for (StyleReferencePart ref : styleRefs) {
                parts.add("style_reference_images", filePart(ref));
            }
        } else if (stylePreset != null && !stylePreset.isBlank()) {
            parts.add("style_preset", stylePreset);
        }
    }

    private static void applyColorPalette(MultiValueMap<String, Object> parts, List<String> paletteHexes) {
        String json = colorPaletteJson(paletteHexes);
        if (json != null) {
            parts.add("color_palette", json);
        }
    }

    /**
     * The {@code color_palette} JSON for the brand colours, halving weights down the ordered list
     * so the lead colour dominates: {@code {"members":[{"color_hex":"#ec4899","color_weight":1.0},…]}}.
     * Malformed entries are dropped (snapshot data is not re-validated upstream); null when nothing valid.
     */
    static String colorPaletteJson(List<String> paletteHexes) {
        if (paletteHexes == null || paletteHexes.isEmpty()) return null;
        List<Map<String, Object>> members = new ArrayList<>();
        double weight = 1.0;
        for (String hex : paletteHexes) {
            String h = hex == null ? "" : hex.trim();
            if (!HEX_COLOR.matcher(h).matches()) continue;
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("color_hex", h);
            member.put("color_weight", weight);
            members.add(member);
            weight = Math.max(MIN_COLOR_WEIGHT, weight / 2);
        }
        if (members.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(Map.of("members", members));
        } catch (Exception e) {
            log.warn("Could not serialize color_palette; sending without it: {}", e.getMessage());
            return null;
        }
    }

    private static HttpEntity<ByteArrayResource> filePart(StyleReferencePart ref) {
        final String filename = ref.filename();
        ByteArrayResource resource = new ByteArrayResource(ref.bytes()) {
            @Override public String getFilename() { return filename; }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                ref.mimeType() == null || ref.mimeType().isBlank() ? "image/png" : ref.mimeType()));
        return new HttpEntity<>(resource, headers);
    }

    private static String styleLabel(List<StyleReferencePart> refs, String preset) {
        if (refs != null && !refs.isEmpty()) return "refs=" + refs.size();
        if (preset != null && !preset.isBlank()) return "preset=" + preset;
        return "style=none";
    }

    /** POST the multipart body, then download the returned image URL (it expires quickly). */
    private byte[] post(String path, MultiValueMap<String, Object> parts) {
        Instant start = Instant.now();
        GenerateResponse resp = ideogramRestClient.post()
                .uri(path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(GenerateResponse.class);
        String url = resp == null || resp.data() == null || resp.data().isEmpty() ? null : resp.data().get(0).url();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Ideogram returned no image URL");
        }
        byte[] bytes = ideogramRestClient.get().uri(URI.create(url)).retrieve().body(byte[].class);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Ideogram image download was empty: " + url);
        }
        log.info("[ideogram v3] {} -> {} KB in {} ms", path, bytes.length / 1024,
                Duration.between(start, Instant.now()).toMillis());
        return bytes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateResponse(List<Datum> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Datum(String url) {}
}
