package com.imin.iminapi.service.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The PNGs a wallet pass ships, read once from the classpath and cached.
 *
 * <p>Every file lives under {@code src/main/resources/wallet/}. They are the real
 * imin mark, not a placeholder, and they were derived — not hand-drawn — so the
 * derivation is written down here and can be repeated:
 *
 * <ul>
 *   <li><b>Source:</b> {@code imin-public/public/logo-mark-light.png}, 1024×1024,
 *       the IMIN grid mark in solid black on an <b>opaque white</b> field. Its
 *       alpha channel is 255 across the whole interior and carries no shape at
 *       all — only a ~4px transparent frame at the very edge. <b>The mask must
 *       therefore be taken from luminance, not from alpha.</b> Keying on alpha
 *       yields a solid white square, which on this pass's near-black background
 *       is a white box where the logo should be.</li>
 *   <li><b>Transform:</b> flatten onto white, {@code mask = 255 - luminance},
 *       crop to the ink (660×780 of the 1024 square), then paint that mask in
 *       {@code #f4f2fb} — the pass {@code foregroundColor} — aspect-fit and
 *       centred in the target box.</li>
 *   <li><b>icon</b> puts the mark at 60% of the side over an opaque
 *       {@code #08070d} field (the pass {@code backgroundColor}, and the same
 *       treatment as the mobile app icon). <b>logo</b> keeps the field
 *       transparent so the mark sits directly on whatever the pass paints
 *       behind it.</li>
 * </ul>
 *
 * <p><b>The fallback is the point of this class.</b> A missing or corrupt file
 * must never fail pass generation: a buyer at a door needs a scannable barcode,
 * and artwork is decoration. On absence or a decode failure this returns the
 * generated placeholder that used to be the only artwork there was, logs once at
 * WARN for that name, and carries on.
 */
final class WalletArtwork {

    private static final Logger log = LoggerFactory.getLogger(WalletArtwork.class);

    /** Classpath root for the committed art. */
    private static final String DIR = "/wallet/";

    /**
     * Decoded-and-validated file bytes, keyed by file name. Bounded by the number
     * of committed files — they are read at most once each and never change at
     * runtime.
     */
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    /** Names already reported missing, so the WARN is once per JVM and not per request. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private WalletArtwork() {
    }

    /**
     * The bytes of {@code classpath:/wallet/{name}}, or a generated {@code w × h}
     * placeholder when that file is absent or does not decode as an image.
     *
     * <p>Returns the file's bytes verbatim — the PNG is not re-encoded, so what
     * Apple receives is byte-for-byte what was committed and reviewed.
     */
    static byte[] load(String name, int fallbackWidth, int fallbackHeight) {
        return CACHE.computeIfAbsent(name, n -> read(n, fallbackWidth, fallbackHeight));
    }

    private static byte[] read(String name, int w, int h) {
        try (InputStream in = WalletArtwork.class.getResourceAsStream(DIR + name)) {
            if (in == null) {
                return placeholder(name, w, h, "not on the classpath at " + DIR + name);
            }
            byte[] bytes = in.readAllBytes();
            // Decode as a check, then throw the decoded image away: a file that
            // ImageIO cannot read is a file Apple cannot read either, and the
            // failure would otherwise surface as a pass that installs and renders
            // blank.
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) {
                return placeholder(name, w, h, "present but not decodable as an image");
            }
            return bytes;
        } catch (IOException | RuntimeException e) {
            return placeholder(name, w, h, e.toString());
        }
    }

    private static byte[] placeholder(String name, int w, int h, String reason) {
        if (WARNED.add(name)) {
            log.warn("[wallet] artwork {} is {} — falling back to a generated {}x{} "
                    + "placeholder. Passes still generate and still scan; they will "
                    + "not carry the brand mark.", name, reason, w, h);
        }
        return solidRect(w, h);
    }

    /**
     * The last-resort mark: the brand field with a small light dot. Deliberately
     * not an attempt to draw the logo — it must be recognisable as "art is
     * missing", not mistaken for the real thing.
     */
    private static byte[] solidRect(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(8, 7, 13));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(244, 242, 251));
            int markSize = Math.max(2, Math.min(w, h) / 4);
            g.fillOval((w - markSize) / 2, (h - markSize) / 2, markSize, markSize);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        try {
            ImageIO.write(img, "PNG", out);
        } catch (IOException e) {
            // ImageIO.write to a ByteArrayOutputStream with a bundled PNG writer
            // has no IO to fail at. Unreachable, and not worth propagating a
            // checked exception through every caller for.
            throw new IllegalStateException("Cannot encode the placeholder PNG", e);
        }
        return out.toByteArray();
    }
}
