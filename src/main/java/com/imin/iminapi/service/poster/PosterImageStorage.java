package com.imin.iminapi.service.poster;

import com.imin.iminapi.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    public String writePng(byte[] bytes) {
        String id = UUID.randomUUID().toString();

        // Prefer object storage (R2): absolute, durable, CDN-cached URL.
        MediaStorage media = mediaStorageProvider.getIfAvailable();
        if (media != null) {
            try {
                String url = media.put("ai-posters/" + id + ".png", bytes, "image/png").url();
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
        return apiPublicBaseUrl + "/images/" + filename;
    }
}
