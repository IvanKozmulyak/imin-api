package com.imin.iminapi.marketing.kit;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Java2D kit images: the poster contained over a blurred copy, real type clear of Instagram's bars; layout apart from drawing. */
@Component
public class StoryKitRenderer {

    public static final int WIDTH = 1080;
    public static final int STORY_HEIGHT = 1920;
    public static final int POST_HEIGHT = 1350;
    /** Text stays between these rows of a 1920-high story. */
    public static final int SAFE_TOP = 250;
    public static final int SAFE_BOTTOM = 1580;

    private static final int MAX_TEXT_WIDTH = 920;
    private static final float JPEG_QUALITY = 0.9f;

    public enum Kind { STORY, POST, COUNTDOWN }

    public record Rect(int x, int y, int w, int h) {
        public int bottom() { return y + h; }
    }

    /** Headline (countdown only), when-line and venue-line; any of them may be null. */
    public record Overlay(String headline, String when, String venue) {}

    /** A drawn line: its text, font, baseline and the box it occupies. */
    public record Line(String text, Font font, int x, int baseline, Rect rect) {}

    public record Layout(int width, int height, Rect poster, List<Line> lines) {
        public List<Rect> textRects() {
            return lines.stream().map(Line::rect).toList();
        }
    }

    private final StoryKitFonts fonts;

    public StoryKitRenderer(StoryKitFonts fonts) {
        this.fonts = fonts;
    }

    public static int heightOf(Kind kind) {
        return kind == Kind.POST ? POST_HEIGHT : STORY_HEIGHT;
    }

    public Layout layout(Kind kind, int posterW, int posterH, Overlay overlay) {
        int height = heightOf(kind);
        if (kind == Kind.POST) {
            return new Layout(WIDTH, height, containCentered(posterW, posterH, WIDTH, height), List.of());
        }
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scratch.createGraphics();
        try {
            List<Line> lines = new ArrayList<>();
            int posterTop;
            Rect poster;
            int whenSize;
            int venueSize;
            if (kind == Kind.STORY) {
                posterTop = 270;
                poster = contain(posterW, posterH, 820, 1025, posterTop);
                whenSize = 64;
                venueSize = 44;
            } else {
                int headlineBottom = addHeadline(g, lines, overlay.headline());
                posterTop = Math.max(560, headlineBottom + 28);
                poster = contain(posterW, posterH, 640, 800, posterTop);
                whenSize = 56;
                venueSize = 40;
            }
            int y = poster.bottom() + 36;
            if (usable(fonts.font(StoryKitFonts.Weight.BOLD, whenSize), overlay.when())) {
                Line when = fitLine(g, overlay.when(), StoryKitFonts.Weight.BOLD, whenSize, y);
                lines.add(when);
                y = when.rect().bottom() + 12;
            }
            if (usable(fonts.font(StoryKitFonts.Weight.SEMI_BOLD, venueSize), overlay.venue())) {
                lines.add(fitLine(g, overlay.venue(), StoryKitFonts.Weight.SEMI_BOLD, venueSize, y));
            }
            return new Layout(WIDTH, height, poster, lines);
        } finally {
            g.dispose();
        }
    }

    public byte[] render(Kind kind, BufferedImage poster, Overlay overlay) {
        Layout layout = layout(kind, poster.getWidth(), poster.getHeight(), overlay);
        BufferedImage canvas = new BufferedImage(layout.width(), layout.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(blurredFill(poster, layout.width(), layout.height()), 0, 0, null);
            Rect p = layout.poster();
            g.drawImage(scaleTo(poster, p.w(), p.h()), p.x(), p.y(), null);
            for (Line line : layout.lines()) {
                g.setFont(line.font());
                g.setColor(new Color(0, 0, 0, 150));
                g.drawString(line.text(), line.x() + 3, line.baseline() + 3);
                g.setColor(Color.WHITE);
                g.drawString(line.text(), line.x(), line.baseline());
            }
        } finally {
            g.dispose();
        }
        return jpeg(canvas);
    }

    // ---- layout helpers ----

    private static int[] fit(int srcW, int srcH, int maxW, int maxH) {
        double scale = Math.min((double) maxW / srcW, (double) maxH / srcH);
        return new int[] {Math.max(1, (int) Math.round(srcW * scale)), Math.max(1, (int) Math.round(srcH * scale))};
    }

    /** Contained in a box of {@code maxW x maxH} whose top is {@code top}, centred across the canvas. */
    private static Rect contain(int srcW, int srcH, int maxW, int maxH, int top) {
        int[] wh = fit(srcW, srcH, maxW, maxH);
        return new Rect((WIDTH - wh[0]) / 2, top, wh[0], wh[1]);
    }

    private static Rect containCentered(int srcW, int srcH, int canvasW, int canvasH) {
        int[] wh = fit(srcW, srcH, canvasW, canvasH);
        return new Rect((canvasW - wh[0]) / 2, (canvasH - wh[1]) / 2, wh[0], wh[1]);
    }

    private boolean usable(Font font, String text) {
        return text != null && !text.isBlank() && font.canDisplayUpTo(text) == -1;
    }

    private Line fitLine(Graphics2D g, String text, StoryKitFonts.Weight weight, int size, int top) {
        int s = size;
        Font font = fonts.font(weight, s);
        FontMetrics fm = g.getFontMetrics(font);
        while (fm.stringWidth(text) > MAX_TEXT_WIDTH && s > 28) {
            s -= 2;
            font = fonts.font(weight, s);
            fm = g.getFontMetrics(font);
        }
        int w = fm.stringWidth(text);
        int x = (WIDTH - w) / 2;
        int h = fm.getAscent() + fm.getDescent();
        return new Line(text, font, x, top + fm.getAscent(), new Rect(x, top, w, h));
    }

    /** Adds one or two headline lines and returns the bottom of the 2-line block they sit in. */
    private int addHeadline(Graphics2D g, List<Line> lines, String headline) {
        int top = 280;
        String text = headline == null ? "" : headline.toUpperCase(Locale.ROOT);
        int size = 104;
        Font font = fonts.font(StoryKitFonts.Weight.EXTRA_BOLD, size);
        if (text.isBlank() || font.canDisplayUpTo(text) != -1) return top;
        List<String> rows;
        while (true) {
            font = fonts.font(StoryKitFonts.Weight.EXTRA_BOLD, size);
            rows = wrap(g.getFontMetrics(font), text);
            if (rows.size() <= 2 && widest(g.getFontMetrics(font), rows) <= MAX_TEXT_WIDTH || size <= 60) break;
            size -= 4;
        }
        FontMetrics fm = g.getFontMetrics(font);
        int lineH = fm.getAscent() + fm.getDescent();
        int block = 2 * lineH;
        int y = top + (block - rows.size() * lineH) / 2;
        for (String row : rows) {
            int w = fm.stringWidth(row);
            int x = (WIDTH - w) / 2;
            lines.add(new Line(row, font, x, y + fm.getAscent(), new Rect(x, y, w, lineH)));
            y += lineH;
        }
        return top + block;
    }

    private static List<String> wrap(FontMetrics fm, String text) {
        if (fm.stringWidth(text) <= MAX_TEXT_WIDTH) return List.of(text);
        String[] words = text.split(" ");
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : words) {
            String next = row.isEmpty() ? word : row + " " + word;
            if (!row.isEmpty() && fm.stringWidth(next) > MAX_TEXT_WIDTH) {
                rows.add(row.toString());
                row = new StringBuilder(word);
            } else {
                row = new StringBuilder(next);
            }
        }
        rows.add(row.toString());
        return rows;
    }

    private static int widest(FontMetrics fm, List<String> rows) {
        return rows.stream().mapToInt(fm::stringWidth).max().orElse(0);
    }

    // ---- pixels ----

    /** The poster cover-scaled to the canvas, blurred by a down-up scale, then darkened for legible type. */
    private static BufferedImage blurredFill(BufferedImage poster, int w, int h) {
        double scale = Math.max((double) w / poster.getWidth(), (double) h / poster.getHeight());
        int cw = Math.max(1, (int) Math.round(poster.getWidth() * scale));
        int ch = Math.max(1, (int) Math.round(poster.getHeight() * scale));
        BufferedImage small = scaleTo(poster, Math.max(1, cw / 24), Math.max(1, ch / 24));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(small, (w - cw) / 2, (h - ch) / 2, cw, ch, null);
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Scales by repeated halving while far larger than the target, so a big poster does not alias. */
    static BufferedImage scaleTo(BufferedImage src, int w, int h) {
        BufferedImage cur = toRgb(src);
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = resample(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return resample(cur, w, h);
    }

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

    private static BufferedImage resample(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] jpeg(BufferedImage img) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
            ios.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode kit JPEG", e);
        } finally {
            writer.dispose();
        }
    }
}
