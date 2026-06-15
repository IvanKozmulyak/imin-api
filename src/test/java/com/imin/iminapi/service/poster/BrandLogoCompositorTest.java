package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrandLogoCompositorTest {

    private final PosterImageStorage storage = mock(PosterImageStorage.class);
    private final BrandLogoCompositor sut = new BrandLogoCompositor(storage);

    /** A solid-colour PNG of the given size, encoded to bytes. */
    private static byte[] solidPng(int w, int h, Color c) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(c);
            g.fillRect(0, 0, w, h);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** A PNG whose left half is opaque colour {@code c}; the right half stays fully transparent. */
    private static byte[] halfTransparentPng(int w, int h, Color c) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(c);
            g.fillRect(0, 0, w / 2, h); // left half opaque; right half left transparent
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static BufferedImage decode(byte[] png) {
        try { return ImageIO.read(new ByteArrayInputStream(png)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void composite_preserves_dimensions_and_changes_bottom_right() {
        byte[] poster = solidPng(800, 1000, new Color(20, 20, 20)); // dark poster
        byte[] logo = solidPng(200, 200, Color.WHITE);              // opaque white mark
        when(storage.download("https://cdn/logo.png")).thenReturn(logo);

        byte[] out = sut.composite(poster, "https://cdn/logo.png");
        BufferedImage rawImg = decode(poster);
        BufferedImage outImg = decode(out);

        assertThat(outImg.getWidth()).isEqualTo(rawImg.getWidth());
        assertThat(outImg.getHeight()).isEqualTo(rawImg.getHeight());

        // The bottom-right corner must differ from the raw poster (logo painted there).
        int x = (int) (rawImg.getWidth() * 0.92);
        int y = (int) (rawImg.getHeight() * 0.94);
        assertThat(outImg.getRGB(x, y)).isNotEqualTo(rawImg.getRGB(x, y));

        // The top-left corner must be untouched.
        assertThat(outImg.getRGB(0, 0)).isEqualTo(rawImg.getRGB(0, 0));
    }

    @Test
    void transparent_logo_pixels_leave_poster_untouched() {
        // A logo that is opaque only in its left half; the right half is fully transparent.
        byte[] poster = solidPng(800, 1000, new Color(10, 10, 10)); // very dark poster
        byte[] logo = halfTransparentPng(200, 200, Color.WHITE);
        when(storage.download("https://cdn/logo.png")).thenReturn(logo);

        byte[] out = sut.composite(poster, "https://cdn/logo.png");
        BufferedImage outImg = decode(out);

        // No scrim plate: a pixel under the logo's transparent half must still read as the raw
        // near-black poster (sum = 30), i.e. the logo composited straight onto the art.
        int x = (int) (outImg.getWidth() * 0.95); // right portion of the bottom-right logo box
        int y = (int) (outImg.getHeight() * 0.90);
        int rgb = outImg.getRGB(x, y);
        int lum = (rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF);
        assertThat(lum).isEqualTo(30);
    }

    @Test
    void cache_invalidate_forces_redownload_on_next_composite() {
        byte[] poster = solidPng(800, 1000, Color.BLACK);
        byte[] logo = solidPng(200, 200, Color.WHITE);
        when(storage.download("https://cdn/logo.png")).thenReturn(logo);

        sut.composite(poster, "https://cdn/logo.png"); // download #1, cached
        sut.composite(poster, "https://cdn/logo.png"); // served from cache
        sut.invalidate("https://cdn/logo.png");
        sut.composite(poster, "https://cdn/logo.png"); // download #2

        org.mockito.Mockito.verify(storage, org.mockito.Mockito.times(2)).download("https://cdn/logo.png");
    }
}
