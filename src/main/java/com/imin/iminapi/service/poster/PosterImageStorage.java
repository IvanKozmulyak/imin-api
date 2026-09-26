package com.imin.iminapi.service.poster;

import com.imin.iminapi.service.ai.provenance.ImageAiMarker;
import com.imin.iminapi.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Storage for generated poster images. Prefers object storage ({@link MediaStorage} → R2) so the
 * returned URL is absolute, durable (survives redeploys — Railway's local disk is ephemeral) and
 * CDN-cached. Falls back to local disk with an ABSOLUTE url (built from the API's public base) when
 * object storage is absent or a put fails, so the image is still loadable cross-origin (the FE is a
 * different origin than the API). A bare relative path is the last resort (dev with no base url).
 */
@Component
public class PosterImageStorage {

    private static final Logger log = LoggerFactory.getLogger(PosterImageStorage.class);

    /** Object-storage key prefix every AI-rendered poster is written under. */
    public static final String AI_POSTER_KEY_PREFIX = "ai-posters/";

    /** Local-disk fallback path segment for the same images. */
    public static final String AI_POSTER_LOCAL_PATH = "/images/";

    /**
     * Whether a poster URL was produced by the AI pipeline.
     *
     * <p>AI Act Art.50 provenance has to be decidable from something the server
     * knows, not from a flag the client sends. Every AI render leaves through
     * {@link #writePng}, which writes either an {@code ai-posters/} object-storage
     * key or a local {@code /images/} file, and nothing else in the application
     * writes to either location — so the URL itself is the evidence.
     *
     * <p>A {@code false} here means "not one of ours", i.e. provenance unknown,
     * not "definitely human-made". Callers stamp {@code null} rather than
     * {@code false} on that branch.
     */
    public static boolean isAiGeneratedPosterUrl(String url) {
        if (url == null || url.isBlank()) return false;
        return url.contains("/" + AI_POSTER_KEY_PREFIX) || url.startsWith(AI_POSTER_KEY_PREFIX)
                || url.contains(AI_POSTER_LOCAL_PATH);
    }

    private final Path storageDir;
    private final ObjectProvider<MediaStorage> mediaStorageProvider;
    private final String apiPublicBaseUrl;
    private final HttpClient downloadClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public PosterImageStorage(
            @Value("${replicate.image.storage-dir}") String storageDir,
            @Value("${imin.ticket.api-public-base-url:}") String apiPublicBaseUrl,
            ObjectProvider<MediaStorage> mediaStorageProvider) {
        this.apiPublicBaseUrl = apiPublicBaseUrl == null ? "" : apiPublicBaseUrl.replaceAll("/+$", "");
        this.mediaStorageProvider = mediaStorageProvider;
        this.storageDir = Path.of(storageDir).toAbsolutePath();
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create image storage directory: " + this.storageDir, e);
        }
    }

    public byte[] download(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = downloadClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("Failed to download image: HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to download image: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted downloading image", e);
        }
    }

    /**
     * Stores an AI render with the machine-readable AI marker embedded (ADR-0005). A format the
     * marker cannot write into is transcoded to PNG first; an undecodable one is stored unmarked.
     */
    public String writePng(byte[] rawBytes, ImageAiMarker.SourceType sourceType) {
        byte[] bytes = markForStorage(rawBytes, sourceType);
        String id = UUID.randomUUID().toString();

        // Prefer object storage (R2): absolute, durable, CDN-cached URL.
        MediaStorage media = mediaStorageProvider.getIfAvailable();
        if (media != null) {
            try {
                String url = media.put(AI_POSTER_KEY_PREFIX + id + ".png", bytes, "image/png").url();
                log.debug("Stored poster to object storage: {} ({} bytes)", url, bytes.length);
                return url;
            } catch (RuntimeException e) {
                log.warn("Object storage put failed ({}) — falling back to local disk", e.getMessage());
            }
        }

        // Fallback: local disk. Return an ABSOLUTE url (API public base) so the FE (a different
        // origin) can load it; bare relative path only if no base url is configured.
        String filename = id + ".png";
        Path path = storageDir.resolve(filename);
        try {
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write image to disk: " + path, e);
        }
        log.debug("Wrote {} ({} bytes)", path, bytes.length);
        return apiPublicBaseUrl + AI_POSTER_LOCAL_PATH + filename;
    }

    // PNG/JPEG marked in place; anything ImageIO decodes is re-encoded as PNG and marked.
    // ponytail: no WebP plugin on the classpath, so a WebP render ships unmarked (WARN) rather than failing.
    private static byte[] markForStorage(byte[] raw, ImageAiMarker.SourceType sourceType) {
        if (ImageAiMarker.supports(raw)) {
            try {
                return ImageAiMarker.mark(raw, sourceType);
            } catch (IllegalArgumentException corrupt) {
                log.warn("AI marker could not be written into the render ({}); trying a PNG re-encode",
                        corrupt.getMessage());
            }
        }
        byte[] png = transcodeToPng(raw);
        if (png != null) {
            try {
                return ImageAiMarker.mark(png, sourceType);
            } catch (IllegalArgumentException unexpected) {
                log.warn("AI marker could not be written into the re-encoded PNG: {}", unexpected.getMessage());
            }
        }
        log.warn("AI poster stored WITHOUT the machine-readable AI marker: undecodable image ({} bytes)",
                raw == null ? 0 : raw.length);
        return raw;
    }

    private static byte[] transcodeToPng(byte[] raw) {
        if (raw == null || raw.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
            if (img == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            return ImageIO.write(img, "PNG", out) ? out.toByteArray() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
