package com.imin.iminapi.marketing.kit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class StoryKitRendererTest {

    private static StoryKitRenderer renderer;

    @BeforeAll
    static void fonts() {
        StoryKitFonts fonts = new StoryKitFonts();
        fonts.load();
        renderer = new StoryKitRenderer(fonts);
    }

    private static final Color[] MARKERS = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};

    /** A square poster with a solid block in each corner; any crop loses at least one of them. */
    private static BufferedImage squarePoster() {
        BufferedImage img = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, 1000, 1000);
        int s = 120;
        g.setColor(MARKERS[0]); g.fillRect(0, 0, s, s);
        g.setColor(MARKERS[1]); g.fillRect(1000 - s, 0, s, s);
        g.setColor(MARKERS[2]); g.fillRect(0, 1000 - s, s, s);
        g.setColor(MARKERS[3]); g.fillRect(1000 - s, 1000 - s, s, s);
        g.dispose();
        return img;
    }

    private static final StoryKitRenderer.Overlay COUNTDOWN_OVERLAY = new StoryKitRenderer.Overlay(
            "Plus qu'une semaine", "SAM. 7 NOV. · 22:00", "La Halle Noire, Paris");

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    @Test
    void storyAndPostHaveInstagramSizes() throws IOException {
        BufferedImage story = decode(renderer.render(StoryKitRenderer.Kind.STORY, squarePoster(), COUNTDOWN_OVERLAY));
        BufferedImage post = decode(renderer.render(StoryKitRenderer.Kind.POST, squarePoster(), COUNTDOWN_OVERLAY));

        assertThat(story.getWidth()).isEqualTo(1080);
        assertThat(story.getHeight()).isEqualTo(1920);
        assertThat(post.getWidth()).isEqualTo(1080);
        assertThat(post.getHeight()).isEqualTo(1350);
    }

    @ParameterizedTest
    @EnumSource(StoryKitRenderer.Kind.class)
    void posterIsContainedWholeWithAllFourCornerMarkers(StoryKitRenderer.Kind kind) throws IOException {
        BufferedImage out = decode(renderer.render(kind, squarePoster(), COUNTDOWN_OVERLAY));
        StoryKitRenderer.Rect p = renderer.layout(kind, 1000, 1000, COUNTDOWN_OVERLAY).poster();

        int inset = 12;
        int[][] corners = {{p.x() + inset, p.y() + inset}, {p.x() + p.w() - inset, p.y() + inset},
                {p.x() + inset, p.y() + p.h() - inset}, {p.x() + p.w() - inset, p.y() + p.h() - inset}};
        for (int i = 0; i < 4; i++) {
            Color c = new Color(out.getRGB(corners[i][0], corners[i][1]));
            assertThat(distance(c, MARKERS[i])).as("%s corner %d is %s", kind, i, c).isLessThan(70);
        }
        // Contained: the poster is the full height or full width of its box, never larger than the canvas.
        assertThat(p.w()).isLessThanOrEqualTo(1080);
        assertThat(p.y()).isGreaterThanOrEqualTo(0);
        assertThat(p.bottom()).isLessThanOrEqualTo(out.getHeight());
    }

    private static double distance(Color a, Color b) {
        return Math.sqrt(Math.pow(a.getRed() - b.getRed(), 2) + Math.pow(a.getGreen() - b.getGreen(), 2)
                + Math.pow(a.getBlue() - b.getBlue(), 2));
    }

    // Instagram covers the top 250 and bottom 340 px of a story with its profile and reply bars.
    @ParameterizedTest
    @EnumSource(value = StoryKitRenderer.Kind.class, names = {"STORY", "COUNTDOWN"})
    void everyTextBoxStaysInsideTheStorySafeZone(StoryKitRenderer.Kind kind) {
        // A tall poster and a long two-line headline are the worst cases for both edges.
        for (int[] size : new int[][] {{1000, 1000}, {1080, 1350}, {900, 1600}}) {
            StoryKitRenderer.Layout layout = renderer.layout(kind, size[0], size[1], COUNTDOWN_OVERLAY);
            assertThat(layout.textRects()).isNotEmpty();
            for (StoryKitRenderer.Rect r : layout.textRects()) {
                assertThat(r.y()).as("%s %dx%d top", kind, size[0], size[1]).isGreaterThanOrEqualTo(StoryKitRenderer.SAFE_TOP);
                assertThat(r.bottom()).as("%s %dx%d bottom", kind, size[0], size[1]).isLessThanOrEqualTo(StoryKitRenderer.SAFE_BOTTOM);
                assertThat(r.x()).isGreaterThanOrEqualTo(0);
                assertThat(r.x() + r.w()).isLessThanOrEqualTo(1080);
            }
        }
    }

    // A line the font cannot draw would print empty boxes; it is left out instead.
    @Test
    void aLineTheFontCannotDrawIsOmitted() {
        StoryKitRenderer.Overlay overlay = new StoryKitRenderer.Overlay("日本語", "SAT 7 NOV · 22:00", "会場");

        StoryKitRenderer.Layout layout = renderer.layout(StoryKitRenderer.Kind.COUNTDOWN, 1000, 1000, overlay);

        assertThat(layout.lines()).extracting(StoryKitRenderer.Line::text).containsExactly("SAT 7 NOV · 22:00");
    }
}
