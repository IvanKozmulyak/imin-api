package com.imin.iminapi.service.poster;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Composites an org's logo onto a generated poster with pure Java2D (the same BufferedImage /
 * Graphics2D toolkit {@link com.imin.iminapi.service.ticket.QrImageRenderer} uses — no new
 * dependency). Deterministic placement: bottom-right, margin 4% of poster width, logo scaled to
 * max 18% of poster width (aspect preserved). The logo is drawn directly onto the poster with no
 * backing plate — the uploaded mark is a transparent PNG, so it composites straight onto the art.
 * The decoded logo is cached by URL (content-addressed) and invalidated on upload/delete via
 * {@link #invalidate(String)}.
 *
 * <p>This class does no error isolation itself — the caller ({@link PosterOrchestrator}) wraps it
 * in try/catch so any failure degrades to the un-composited poster. Generation never fails over
 * a decoration.
 */
@Component
public class BrandLogoCompositor {

    private static final double MARGIN_FRACTION = 0.04;     // 4% of poster width
    private static final double LOGO_MAX_FRACTION = 0.18;   // 18% of poster width

    /**
     * Cap on distinct decoded logos held in memory. The key is a content-addressed URL, so an
     * unbounded map gained one permanent entry per distinct logo of every org that ever composited
     * a poster — and the retained value is the DECODED image (width*height*4 bytes), not the 2 MB
     * upload. Decoding again costs nothing next to the Ideogram render that precedes it.
     */
    static final int MAX_CACHED_LOGOS = 32;

    private final PosterImageStorage storage;

    /** Access-ordered LRU, bounded by {@link #MAX_CACHED_LOGOS}; see {@link #invalidate(String)}. */
    private final Map<String, BufferedImage> logoCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > MAX_CACHED_LOGOS;
                }
            });

    public BrandLogoCompositor(PosterImageStorage storage) {
        this.storage = storage;
    }

    /** Drop the cached decoded logo (call on logo upload/delete, keyed by URL). */
    public void invalidate(String logoUrl) {
        logoCache.remove(logoUrl);
    }

    /**
     * Returns a new PNG with the logo composited bottom-right. Throws on any failure (download,
     * decode, encode) — the caller isolates.
     */
    public byte[] composite(byte[] posterPng, String logoUrl) {
        BufferedImage poster = decode(posterPng);
        // Download outside the map lock: a miss must not serialise concurrent generations for
        // different orgs. A racing duplicate download is harmless — the decode is idempotent.
        BufferedImage logo = logoCache.get(logoUrl);
        if (logo == null) {
            logo = decode(storage.download(logoUrl));
            logoCache.put(logoUrl, logo);
        }

        int pw = poster.getWidth();
        int ph = poster.getHeight();

        // Scale the logo to at most 18% of poster width, preserving aspect.
        int targetW = (int) Math.round(pw * LOGO_MAX_FRACTION);
        double scale = (double) targetW / logo.getWidth();
        int logoW = Math.max(1, (int) Math.round(logo.getWidth() * scale));
        int logoH = Math.max(1, (int) Math.round(logo.getHeight() * scale));

        int margin = (int) Math.round(pw * MARGIN_FRACTION);
        int logoX = pw - margin - logoW;
        int logoY = ph - margin - logoH;

        // Work on a copy so the input bytes/poster image are never mutated.
        BufferedImage out = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(poster, 0, 0, null);

        // Logo straight on top with SrcOver so the transparent PNG composites onto the art — no scrim.
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        g.drawImage(logo, logoX, logoY, logoW, logoH, null);
        g.dispose();

        return encode(out);
    }

    private static BufferedImage decode(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) throw new IllegalStateException("could not decode image");
            return img;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to decode image for composite", e);
        }
    }

    private static byte[] encode(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode composited PNG", e);
        }
    }
}
