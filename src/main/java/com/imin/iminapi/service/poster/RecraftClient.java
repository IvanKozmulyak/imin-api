package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.imin.iminapi.dto.Rgb;
import com.imin.iminapi.dto.StyleMode;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recraft image-generation client, retained for per-vibe style training
 * ({@code POST /styles}) used by the (off-render-path) vibe style-training endpoint.
 *
 * <p>Generation uses {@code POST /images/generations} with {@code model=recraftv3}
 * and {@code response_format=b64_json}, returning materialized PNG bytes. When a
 * trained per-vibe {@code style_id} is supplied it is sent (and is mutually
 * exclusive with the {@code style} base param per the Recraft API); otherwise a
 * base {@code style} is sent so generation still works for untrained vibes.
 *
 * <p>Style training uses {@code POST /styles} (multipart): a base {@code style}
 * plus up to {@link #MAX_STYLE_FILES} reference image files, returning the
 * {@code id} used later as {@code style_id}.
 *
 * <p>The Recraft generation endpoint conditions on the trained style, not on
 * inline reference bytes, so {@code referenceImageBytes} passed to
 * {@link #generate} is only used to decide whether references exist (for logging
 * / future use); the per-vibe aesthetic travels through {@code styleId}.
 */
@Component
public class RecraftClient {

    private static final Logger log = LoggerFactory.getLogger(RecraftClient.class);

    /** Recraft caps custom-style training at 5 reference images. */
    static final int MAX_STYLE_FILES = 5;

    /** Base style used when no trained style_id exists, and as the training base. */
    static final String DEFAULT_BASE_STYLE = "realistic_image";
    /** The downstream storage/compositor path expects raster bytes that can be embedded as PNG. */
    static final String DEFAULT_IMAGE_FORMAT = "png";
    /** Recraft V3 {@code controls.artistic_level} (0–5). 4 = strong styling for curated substyles. */
    static final int ARTISTIC_LEVEL = 4;

    private final RestClient recraftRestClient;
    private final String model;
    private final String baseStyle;

    public RecraftClient(
            RestClient recraftRestClient,
            @Value("${recraft.model:recraftv3}") String model,
            @Value("${recraft.base-style:" + DEFAULT_BASE_STYLE + "}") String baseStyle) {
        this.recraftRestClient = recraftRestClient;
        this.model = model;
        this.baseStyle = (baseStyle == null || baseStyle.isBlank()) ? DEFAULT_BASE_STYLE : baseStyle;
    }

    public record RecraftResult(byte[] imageBytes, Duration generationTime, String model) {}

    /**
     * How to style one generation. {@code style} and {@code style_id} are mutually exclusive in the
     * Recraft API, so the two modes are kept apart:
     * <ul>
     *   <li>{@link StyleMode#TRAINED_STYLE_ID} — send {@code style_id} when present, else the base
     *       {@code style} so untrained vibes still render.</li>
     *   <li>{@link StyleMode#CURATED_SUBSTYLE} — send base {@code style=realistic_image} +
     *       {@code substyle} + {@code controls.colors}/{@code artistic_level}; never a {@code style_id}.</li>
     * </ul>
     */
    public record RenderSpec(StyleMode mode, String styleId, String substyle, List<Rgb> colors) {
        public static RenderSpec trained(String styleId) {
            return new RenderSpec(StyleMode.TRAINED_STYLE_ID, styleId, null, List.of());
        }
        public static RenderSpec curated(String substyle, List<Rgb> colors) {
            return new RenderSpec(StyleMode.CURATED_SUBSTYLE, null, substyle, colors == null ? List.of() : colors);
        }
    }

    /** Generate one image under the given {@link RenderSpec}. */
    public RecraftResult generate(
            String prompt,
            String aspectRatio,
            RenderSpec render,
            List<byte[]> referenceImageBytes) {
        String size = mapAspectRatio(aspectRatio);
        int refCount = referenceImageBytes == null ? 0 : referenceImageBytes.size();
        RenderSpec spec = render == null ? RenderSpec.trained(null) : render;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        body.put("model", model);
        body.put("size", size);
        body.put("n", 1);
        body.put("response_format", "b64_json");
        body.put("image_format", DEFAULT_IMAGE_FORMAT);

        String modeLabel;
        if (spec.mode() == StyleMode.CURATED_SUBSTYLE) {
            // Photo-led vibes: curated realistic_image + substyle + palette controls. Never style_id.
            body.put("style", baseStyle);
            if (isBlank(spec.substyle())) {
                log.warn("Recraft CURATED_SUBSTYLE requested with no substyle — sending base style only");
            } else {
                body.put("substyle", spec.substyle());
            }
            List<Map<String, Object>> colors = toColorControls(spec.colors());
            Map<String, Object> controls = new LinkedHashMap<>();
            if (!colors.isEmpty()) controls.put("colors", colors);
            controls.put("artistic_level", ARTISTIC_LEVEL);
            body.put("controls", controls);
            modeLabel = "curated:" + baseStyle + "/" + (isBlank(spec.substyle()) ? "(none)" : spec.substyle())
                    + " colors=" + colors.size() + " artistic_level=" + ARTISTIC_LEVEL;
        } else {
            boolean hasStyle = !isBlank(spec.styleId());
            if (hasStyle) body.put("style_id", spec.styleId());
            else body.put("style", baseStyle);
            modeLabel = hasStyle ? "trained:" + spec.styleId() : "trained:base/" + baseStyle;
        }

        log.info("[Recraft image] prompt sent (model={}, size={}, mode={}):\n{}", model, size, modeLabel, prompt);

        Instant start = Instant.now();
        ImagesResponse resp = recraftRestClient.post()
                .uri("/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ImagesResponse.class);
        byte[] bytes = decodeSingle(resp);
        Duration elapsed = Duration.between(start, Instant.now());
        log.info("Recraft image ({}) generated in {} ms (size={}, image_format={}, mode={}, refs={})",
                model, elapsed.toMillis(), size, DEFAULT_IMAGE_FORMAT, modeLabel, refCount);
        return new RecraftResult(bytes, elapsed, model);
    }

    /** Map a style-card palette to Recraft {@code controls.colors}: a list of {@code {"rgb":[r,g,b]}}. */
    private static List<Map<String, Object>> toColorControls(List<Rgb> colors) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (colors == null) return out;
        for (Rgb c : colors) {
            if (c == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rgb", List.of(c.r(), c.g(), c.b()));
            out.add(entry);
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Train a reusable Recraft style from curated reference images. Posts up to
     * {@link #MAX_STYLE_FILES} files (sorted/capped by the caller is fine; we cap
     * defensively here too) plus the base {@code style}, returning the new
     * {@code style_id}.
     */
    public String createStyle(List<byte[]> referenceImages) {
        return createStyle(baseStyle, referenceImages);
    }

    public String createStyle(String style, List<byte[]> referenceImages) {
        if (referenceImages == null || referenceImages.isEmpty()) {
            throw new IllegalArgumentException("Recraft style training requires at least one reference image");
        }
        String effectiveStyle = (style == null || style.isBlank()) ? baseStyle : style;

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("style", effectiveStyle);
        int count = Math.min(referenceImages.size(), MAX_STYLE_FILES);
        for (int i = 0; i < count; i++) {
            byte[] bytes = referenceImages.get(i);
            // Recraft accepts png/jpg/webp; curated flyer folders mix all three. Label each
            // part by its real format — sniffed from the magic bytes — so the filename and
            // Content-Type match the payload instead of being hard-coded to png (which would
            // mislabel webp/jpg bytes and risk rejection).
            ImageFormat fmt = sniffImageFormat(bytes);
            final String filename = "ref_" + i + "." + fmt.extension;
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename; }
            };
            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(fmt.mediaType);
            // Recraft expects file fields named file (single) / file1..fileN; we use
            // the indexed form which the API accepts for one or many references.
            parts.add("file" + (i + 1), new HttpEntity<>(resource, partHeaders));
        }
        if (referenceImages.size() > MAX_STYLE_FILES) {
            log.warn("Recraft style training received {} references; capping at {} (API max)",
                    referenceImages.size(), MAX_STYLE_FILES);
        }

        Instant start = Instant.now();
        StyleResponse resp = recraftRestClient.post()
                .uri("/styles")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(StyleResponse.class);
        if (resp == null || resp.id() == null || resp.id().isBlank()) {
            throw new IllegalStateException("Recraft style creation returned no id");
        }
        log.info("Recraft style trained in {} ms: style_id={} (base={}, files={})",
                Duration.between(start, Instant.now()).toMillis(), resp.id(), effectiveStyle, count);
        return resp.id();
    }

    /**
     * Map a poster aspect ratio to a Recraft {@code size} (WxH). Recraft accepts a
     * fixed set of sizes; we snap the common poster ratios to the nearest supported
     * dimension and default to portrait.
     */
    static String mapAspectRatio(String aspectRatio) {
        if (aspectRatio == null) return "1024x1365";
        return switch (aspectRatio) {
            case "1:1" -> "1024x1024";
            case "3:2", "16:9", "1.91:1" -> "1365x1024";
            case "9:16" -> "1024x1820";
            // 3:4, 4:5 portrait poster ratios snap to the tall Recraft size.
            default -> "1024x1365";
        };
    }

    private byte[] decodeSingle(ImagesResponse resp) {
        if (resp == null || resp.data() == null || resp.data().isEmpty()) {
            throw new IllegalStateException("Recraft image response contained no data");
        }
        ImageDatum first = resp.data().get(0);
        if (first.b64_json() == null || first.b64_json().isBlank()) {
            throw new IllegalStateException(
                    "Recraft image response missing b64_json (url-only responses are not supported)");
        }
        return Base64.getDecoder().decode(first.b64_json());
    }

    /** Image formats Recraft accepts for style training, with canonical extension + MIME. */
    private enum ImageFormat {
        PNG("png", MediaType.IMAGE_PNG),
        JPEG("jpg", MediaType.IMAGE_JPEG),
        WEBP("webp", MediaType.valueOf("image/webp"));

        final String extension;
        final MediaType mediaType;

        ImageFormat(String extension, MediaType mediaType) {
            this.extension = extension;
            this.mediaType = mediaType;
        }
    }

    /** Detect the image format from the leading magic bytes; defaults to PNG when unrecognized. */
    private static ImageFormat sniffImageFormat(byte[] b) {
        if (b != null) {
            if (b.length >= 3
                    && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
                return ImageFormat.JPEG; // FF D8 FF
            }
            if (b.length >= 12
                    && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
                return ImageFormat.WEBP; // "RIFF"...."WEBP"
            }
            if (b.length >= 8
                    && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                    && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A
                    && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
                return ImageFormat.PNG; // 89 50 4E 47 0D 0A 1A 0A
            }
        }
        return ImageFormat.PNG; // safe default — Recraft accepts png/jpg/webp
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImagesResponse(List<ImageDatum> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImageDatum(String b64_json, String url) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StyleResponse(String id) {}
}
