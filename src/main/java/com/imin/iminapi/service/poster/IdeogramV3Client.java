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
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Native Ideogram V3 client (direct API, not Replicate). Auth is the {@code Api-Key} header
 * (see {@link com.imin.iminapi.config.IdeogramImageConfig}). The art director already wrote the
 * prompt, so {@code magic_prompt=OFF}. Ideogram V3 accepts EXACTLY ONE style control, so curated
 * reference images win when present and the per-vibe {@code style_preset} is the no-refs fallback.
 * The response carries a temporary image URL, which is downloaded immediately (the link expires).
 *
 * <p>The initial {@code generate} renders at the QUALITY tier; the corrective {@code remix} (which
 * only fixes baked text on a render we already liked) renders at the cheaper/faster TURBO tier.
 */
@Component
public class IdeogramV3Client {

    private static final Logger log = LoggerFactory.getLogger(IdeogramV3Client.class);

    static final String GENERATE_PATH = "/v1/ideogram-v3/generate";
    static final String REMIX_PATH = "/v1/ideogram-v3/remix";
    static final String ASPECT_RATIO = "4x5";   // native uses NxM
    static final String MAGIC_PROMPT = "OFF";

    private final RestClient ideogramRestClient;
    private final String generateSpeed;
    private final String remixSpeed;
    private final boolean copyrightDetection;

    public IdeogramV3Client(
            RestClient ideogramRestClient,
            @Value("${ideogram.generate.rendering-speed:QUALITY}") String generateSpeed,
            @Value("${ideogram.remix.rendering-speed:TURBO}") String remixSpeed,
            @Value("${ideogram.copyright-detection:true}") boolean copyrightDetection) {
        this.ideogramRestClient = ideogramRestClient;
        this.generateSpeed = (generateSpeed == null || generateSpeed.isBlank()) ? "QUALITY" : generateSpeed;
        this.remixSpeed = (remixSpeed == null || remixSpeed.isBlank()) ? "TURBO" : remixSpeed;
        this.copyrightDetection = copyrightDetection;
    }

    public record IdeogramResult(byte[] imageBytes, long seed) {}

    /** Text-to-image generate (QUALITY tier). */
    public IdeogramResult generate(String prompt, long seed, List<StyleReferencePart> styleRefs, String stylePreset) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("prompt", prompt);
        parts.add("aspect_ratio", ASPECT_RATIO);
        parts.add("rendering_speed", generateSpeed);
        parts.add("magic_prompt", MAGIC_PROMPT);
        parts.add("enable_copyright_detection", String.valueOf(copyrightDetection));
        parts.add("seed", String.valueOf(seed));
        applyStyleControl(parts, styleRefs, stylePreset);
        log.info("[ideogram v3 generate] promptLen={} speed={} {} seed={}",
                prompt.length(), generateSpeed, styleLabel(styleRefs, stylePreset), seed);
        return new IdeogramResult(post(GENERATE_PATH, parts), seed);
    }

    /** Image-conditioned remix (TURBO tier): feed a failing image back with a corrective prompt to fix the text. */
    public IdeogramResult remix(byte[] image, String prompt, int imageWeight, long seed,
                                List<StyleReferencePart> styleRefs, String stylePreset) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("image", filePart(new StyleReferencePart(image, "source.png", "image/png")));
        parts.add("prompt", prompt);
        parts.add("image_weight", String.valueOf(imageWeight));
        parts.add("aspect_ratio", ASPECT_RATIO);
        parts.add("rendering_speed", remixSpeed);
        parts.add("magic_prompt", MAGIC_PROMPT);
        parts.add("seed", String.valueOf(seed));
        applyStyleControl(parts, styleRefs, stylePreset);
        log.info("[ideogram v3 remix] promptLen={} weight={} {} seed={}",
                prompt.length(), imageWeight, styleLabel(styleRefs, stylePreset), seed);
        return new IdeogramResult(post(REMIX_PATH, parts), seed);
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
