package com.imin.iminapi.marketing.kit;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/** The bundled OFL fonts, loaded at startup; without the native font stack it logs ERROR and reports unavailable. */
@Component
public class StoryKitFonts {

    private static final Logger log = LoggerFactory.getLogger(StoryKitFonts.class);

    public enum Weight { SEMI_BOLD, BOLD, EXTRA_BOLD }

    private volatile Font semiBold;
    private volatile Font bold;
    private volatile Font extraBold;
    private volatile boolean available;

    @PostConstruct
    void load() {
        try {
            semiBold = read("Inter-SemiBold.ttf");
            bold = read("Inter-Bold.ttf");
            extraBold = read("Inter-ExtraBold.ttf");
            probe(bold);
            available = true;
            log.info("Story kit fonts loaded");
        } catch (Throwable t) {
            // Throwable: a missing libfreetype surfaces as an Error, not an Exception.
            available = false;
            log.error("Story kit fonts unavailable; Instagram kits are switched off: {}", t.toString());
        }
    }

    public boolean available() {
        return available;
    }

    public Font font(Weight weight, float size) {
        Font base = switch (weight) {
            case SEMI_BOLD -> semiBold;
            case BOLD -> bold;
            case EXTRA_BOLD -> extraBold;
        };
        if (base == null) throw new IllegalStateException("Story kit fonts are not loaded");
        return base.deriveFont(size);
    }

    private static Font read(String name) throws Exception {
        try (InputStream in = StoryKitFonts.class.getResourceAsStream("/fonts/story-kit/" + name)) {
            if (in == null) throw new IOException("Missing bundled font " + name);
            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
    }

    // Measuring a glyph forces the native rasteriser to load now, not on the first organizer request.
    private static void probe(Font f) {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setFont(f.deriveFont(12f));
            if (g.getFontMetrics().stringWidth("Ag") <= 0) throw new IllegalStateException("Font measures zero width");
        } finally {
            g.dispose();
        }
    }
}
