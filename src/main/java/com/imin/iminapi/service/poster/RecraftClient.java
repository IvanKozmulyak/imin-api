package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import java.util.List;

/**
 * Recraft style-training client. Ideogram V3 is the sole renderer, so this class holds ONLY
 * {@code POST /styles}, which backs the (off-render-path) vibe style-training endpoint: a base
 * {@code style} plus up to {@link #MAX_STYLE_FILES} reference image files, returning the
 * {@code id}.
 *
 * <p>The image-generation half ({@code POST /images/generations}, {@code RenderSpec},
 * aspect-ratio mapping, colour controls) was removed: nothing in src/main called it after the
 * native Ideogram migration.
 */
@Component
public class RecraftClient {

    private static final Logger log = LoggerFactory.getLogger(RecraftClient.class);

    /** Recraft caps custom-style training at 5 reference images. */
    static final int MAX_STYLE_FILES = 5;

    /** Base style used when no trained style_id exists, and as the training base. */
    static final String DEFAULT_BASE_STYLE = "realistic_image";

    private final RestClient recraftRestClient;
    private final String baseStyle;

    public RecraftClient(
            RestClient recraftRestClient,
            @Value("${recraft.base-style:" + DEFAULT_BASE_STYLE + "}") String baseStyle) {
        this.recraftRestClient = recraftRestClient;
        this.baseStyle = (baseStyle == null || baseStyle.isBlank()) ? DEFAULT_BASE_STYLE : baseStyle;
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
    record StyleResponse(String id) {}
}
