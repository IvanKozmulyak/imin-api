package com.imin.iminapi.marketing.kit;

import com.imin.iminapi.service.ai.provenance.ImageAiMarker;
import com.imin.iminapi.service.poster.PosterImageStorage;
import com.imin.iminapi.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.UUID;

/** Reads the poster only from our own storage, capped before decoding; writes under story-kits/, never ai-posters/. */
@Component
public class KitImageStorage {

    private static final Logger log = LoggerFactory.getLogger(KitImageStorage.class);

    public static final String KEY_PREFIX = "story-kits/";
    static final int MAX_SIDE = 8000;
    static final long MAX_PIXELS = 25_000_000L;
    /** Decoded long side aimed for; never below the tallest draw (the 1350 px feed post). */
    static final int DECODE_LONG_SIDE = 2160;

    /** The poster cannot be used: not ours, gone, too large or not an image the server can read. */
    public static class PosterUnreadableException extends RuntimeException {
        public PosterUnreadableException(String message) { super(message); }
    }

    public record Uploaded(String storageKey, String url) {}

    private final ObjectProvider<MediaStorage> media;
    private final PosterImageStorage posters;
    private final long maxPosterBytes;

    public KitImageStorage(ObjectProvider<MediaStorage> media, PosterImageStorage posters,
                           @Value("${imin.story-kit.max-poster-bytes:20971520}") long maxPosterBytes) {
        this.media = media;
        this.posters = posters;
        this.maxPosterBytes = maxPosterBytes;
    }

    /** Object storage is configured (the kit has nowhere to put its images otherwise). */
    public boolean storageAvailable() {
        return media.getIfAvailable() != null;
    }

    /** The poster URL points into our own object storage or our own local-disk fallback. */
    public boolean isOwnPoster(String url) {
        if (url == null || url.isBlank()) return false;
        MediaStorage m = media.getIfAvailable();
        if (m != null && m.keyFor(url) != null) return true;
        return posters.isLocalImageUrl(url);
    }

    /** Reads and decodes our own poster; refuses anything else before a byte is fetched. */
    public BufferedImage loadPoster(String url) {
        byte[] bytes = readOwn(url);
        if (bytes == null) throw new PosterUnreadableException("Poster is not in our storage");
        return decodeGuarded(bytes);
    }

    private byte[] readOwn(String url) {
        try {
            MediaStorage m = media.getIfAvailable();
            String key = m == null ? null : m.keyFor(url);
            if (key != null) return m.read(key, maxPosterBytes);
            return posters.readLocal(url, maxPosterBytes);
        } catch (IllegalStateException tooLarge) {
            throw new PosterUnreadableException(tooLarge.getMessage());
        }
    }

    // Dimensions are read from the header before any pixel is decoded, so a small file cannot expand into a bomb.
    static BufferedImage decodeGuarded(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) throw new PosterUnreadableException("Poster is not a readable image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
                    throw new PosterUnreadableException("Poster is too large to use");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = subsampling(Math.max(w, h));
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                return toRgb(reader.read(0, param));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof PosterUnreadableException p) throw p;
            throw new PosterUnreadableException("Poster could not be decoded");
        }
    }

    static int subsampling(int longSide) {
        int step = Math.max(1, (int) Math.ceil(longSide / (double) DECODE_LONG_SIDE));
        while (step > 1 && Math.ceil(longSide / (double) step) < StoryKitRenderer.POST_HEIGHT) step--;
        return step;
    }

    // One RGB copy here, so the renderer's ten scalings never convert the poster again.
    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Stores one JPEG, marked as a composite with AI media only when the poster it derives from is AI. */
    public Uploaded put(UUID eventId, String assetKey, byte[] jpeg, boolean aiDerived) {
        MediaStorage m = media.getIfAvailable();
        if (m == null) throw new IllegalStateException("Object storage is not configured");
        byte[] bytes = aiDerived
                ? ImageAiMarker.mark(jpeg, ImageAiMarker.SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA)
                : jpeg;
        String key = KEY_PREFIX + eventId + "/" + UUID.randomUUID() + "-" + assetKey + ".jpg";
        return new Uploaded(key, m.put(key, bytes, "image/jpeg").url());
    }

    /** Bytes of one of our kit objects; a key outside {@code story-kits/} is never read. */
    public byte[] readKit(String storageKey) {
        MediaStorage m = media.getIfAvailable();
        if (m == null || storageKey == null || !storageKey.startsWith(KEY_PREFIX)) return null;
        return m.read(storageKey, 10L * 1024 * 1024);
    }

    /** Best-effort delete: a failure is logged and never replaces the caller's result or exception. */
    public void deleteQuietly(Collection<String> storageKeys) {
        MediaStorage m = media.getIfAvailable();
        if (m == null) return;
        for (String key : storageKeys) {
            try {
                m.delete(key);
            } catch (RuntimeException e) {
                log.warn("Could not delete kit object {}: {}", key, e.getMessage());
            }
        }
    }
}
