package com.imin.iminapi.service.poster;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Cuts the four edge strips of a poster and enlarges them, so fine print and vertical credit lines
 * the full-image read skims over are big enough to read.
 */
public final class PosterEdgeBands {

    static final double BAND_FRACTION = 0.12;
    static final int SCALE = 2;
    static final List<String> BAND_NAMES = List.of("top", "bottom", "left", "right");

    private PosterEdgeBands() {}

    public record Band(String name, byte[] png) {}

    /** Top, bottom, left, right — in that order; side strips span the full height. */
    public static List<Band> crop(byte[] png) {
        BufferedImage src;
        try {
            src = png == null ? null : ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new IllegalStateException("poster image could not be decoded for the edge pass", e);
        }
        if (src == null) throw new IllegalStateException("poster image could not be decoded for the edge pass");
        int w = src.getWidth();
        int h = src.getHeight();
        int bandH = Math.max(1, (int) (h * BAND_FRACTION));
        int bandW = Math.max(1, (int) (w * BAND_FRACTION));
        return List.of(
                band("top", src, 0, 0, w, bandH),
                band("bottom", src, 0, h - bandH, w, bandH),
                band("left", src, 0, 0, bandW, h),
                band("right", src, w - bandW, 0, bandW, h));
    }

    private static Band band(String name, BufferedImage src, int x, int y, int w, int h) {
        BufferedImage out = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, w * SCALE, h * SCALE, x, y, x + w, y + h, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(out, "png", bytes);
        } catch (IOException e) {
            throw new IllegalStateException("edge band could not be encoded", e);
        }
        return new Band(name, bytes.toByteArray());
    }
}
