package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PosterEdgeBandsTest {

    // Catches: fine print in a corner sent to the reader as the wrong strip, or not sent at all.
    @Test
    void crops_fourEdgeBands_atTwiceTheirSize() throws Exception {
        BufferedImage poster = new BufferedImage(896, 1120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = poster.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 896, 1120);
        g.setColor(Color.BLACK);
        g.fillRect(10, 1080, 30, 30);   // bottom-left corner mark
        g.fillRect(430, 540, 30, 30);   // centre mark
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(poster, "png", png);

        List<PosterEdgeBands.Band> bands = PosterEdgeBands.crop(png.toByteArray());

        assertThat(bands).extracting(PosterEdgeBands.Band::name).containsExactly("top", "bottom", "left", "right");
        BufferedImage top = decode(bands.get(0));
        BufferedImage bottom = decode(bands.get(1));
        BufferedImage left = decode(bands.get(2));
        BufferedImage right = decode(bands.get(3));
        assertThat(List.of(top.getWidth(), top.getHeight())).containsExactly(1792, 268);
        assertThat(List.of(bottom.getWidth(), bottom.getHeight())).containsExactly(1792, 268);
        assertThat(List.of(left.getWidth(), left.getHeight())).containsExactly(214, 2240);
        assertThat(List.of(right.getWidth(), right.getHeight())).containsExactly(214, 2240);
        assertThat(darkPixels(top)).isZero();
        assertThat(darkPixels(bottom)).isPositive();
        assertThat(darkPixels(left)).isPositive();
        assertThat(darkPixels(right)).isZero();
    }

    private static BufferedImage decode(PosterEdgeBands.Band band) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(band.png()));
    }

    private static int darkPixels(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFF) < 128) n++;
            }
        }
        return n;
    }
}
