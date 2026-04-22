package com.imin.iminapi.service.poster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Local filesystem storage for Phase 1. Swap implementation for R2-backed
 * storage in Phase 2 without changing callers.
 */
@Component
public class PosterImageStorage {

    private static final Logger log = LoggerFactory.getLogger(PosterImageStorage.class);

    private final Path storageDir;
    private final HttpClient downloadClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public PosterImageStorage(@Value("${replicate.image.storage-dir}") String storageDir) {
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
        String filename = UUID.randomUUID() + ".png";
        Path path = storageDir.resolve(filename);
        try {
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write image to disk: " + path, e);
        }
        log.debug("Wrote {} ({} bytes)", path, bytes.length);
        return "/images/" + filename;
    }
}
