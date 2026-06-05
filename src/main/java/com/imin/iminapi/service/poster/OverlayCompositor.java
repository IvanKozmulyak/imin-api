package com.imin.iminapi.service.poster;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

@Component
public class OverlayCompositor {

    private static final Logger log = LoggerFactory.getLogger(OverlayCompositor.class);

    private final int qrSizePx;
    private final int addressMaxChars;
    private final boolean qrEnabled;

    public OverlayCompositor(
            @Value("${poster.overlay.qr-size-px:120}") int qrSizePx,
            @Value("${poster.overlay.address-max-chars:80}") int addressMaxChars,
            // QR rendering is OFF by default for now; set poster.overlay.qr-enabled=true to restore it.
            @Value("${poster.overlay.qr-enabled:false}") boolean qrEnabled) {
        this.qrSizePx = qrSizePx;
        this.addressMaxChars = addressMaxChars;
        this.qrEnabled = qrEnabled;
    }

    public record Input(byte[] basePng, String qrPayload, String addressText) {}

    public byte[] applyOverlays(Input input) {
        boolean drawQr   = qrEnabled && input.qrPayload() != null && !input.qrPayload().isBlank();
        boolean drawAddr = input.addressText() != null && !input.addressText().isBlank();
        if (!drawQr && !drawAddr) {
            return input.basePng();
        }
        try {
            BufferedImage base = ImageIO.read(new ByteArrayInputStream(input.basePng()));
            if (base == null) {
                // ImageIO has no decoder for this format (e.g. WebP, which Recraft V3 returns).
                // Don't fail the whole variant — return the raw art without the QR/address band.
                // (The Satori compositor on imin-public decodes WebP via resvg for the text layer.)
                log.warn("basePng is not ImageIO-decodable ({} bytes) — skipping QR/address overlay",
                        input.basePng() == null ? 0 : input.basePng().length);
                return input.basePng();
            }
            Graphics2D g = base.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                // The address band needs the JVM's native font stack (libfreetype). On a slim container
                // without it, that init throws an Error (NoClassDefFoundError/UnsatisfiedLinkError), which
                // is NOT a RuntimeException — so guard it explicitly and skip the band rather than fail the
                // variant. The QR uses no fonts and still renders.
                if (drawAddr) {
                    try {
                        drawAddressBand(g, base.getWidth(), base.getHeight(), input.addressText());
                    } catch (Throwable t) {
                        log.warn("Skipping address band — text rendering unavailable (missing native fonts?): {}",
                                t.toString());
                    }
                }
                if (drawQr) {
                    try {
                        drawQrCode(g, base.getWidth(), base.getHeight(), input.qrPayload());
                    } catch (Throwable t) {
                        log.warn("Skipping QR overlay: {}", t.toString());
                    }
                }
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(base, "png", out);
            return out.toByteArray();
        } catch (Throwable t) {
            // The overlay is cosmetic — never fail the poster variant over it. Return the raw art.
            log.warn("Overlay compositing failed; returning raw poster art: {}", t.toString());
            return input.basePng();
        }
    }

    private void drawAddressBand(Graphics2D g, int w, int h, String rawText) {
        String text = rawText.length() > addressMaxChars
                ? rawText.substring(0, addressMaxChars - 1) + "…"
                : rawText;

        int bandHeight = Math.max(60, h / 14);
        int bandY = h - bandHeight;

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
        g.setColor(new Color(0, 0, 0));
        g.fillRect(0, bandY, w, bandHeight);
        g.setComposite(AlphaComposite.SrcOver);

        Font font = chooseFont(Math.max(16, bandHeight / 3));
        g.setFont(font);
        g.setColor(Color.WHITE);

        FontRenderContext frc = g.getFontRenderContext();
        Rectangle2D bounds = font.getStringBounds(text, frc);
        int textX = Math.max(24, (w - (int) bounds.getWidth()) / 2);
        int textY = bandY + (bandHeight + (int) bounds.getHeight()) / 2 - 4;
        g.drawString(text, textX, textY);
    }

    private void drawQrCode(Graphics2D g, int w, int h, String payload) {
        BufferedImage qr = renderQr(payload, qrSizePx);
        int margin = Math.max(16, w / 50);
        int x = w - qr.getWidth() - margin;
        int y = h - qr.getHeight() - margin - Math.max(60, h / 14) - margin; // above address band

        // White padding square behind the QR for contrast
        int pad = 8;
        g.setColor(Color.WHITE);
        g.fillRect(x - pad, y - pad, qr.getWidth() + pad * 2, qr.getHeight() + pad * 2);
        g.setColor(new Color(0, 0, 0, 120));
        g.setStroke(new BasicStroke(1f));
        g.drawRect(x - pad, y - pad, qr.getWidth() + pad * 2 - 1, qr.getHeight() + pad * 2 - 1);

        g.drawImage(qr, x, y, null);
    }

    BufferedImage renderQr(String payload, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(payload, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    img.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            return img;
        } catch (WriterException e) {
            throw new RuntimeException("Failed to render QR for payload: " + payload, e);
        }
    }

    private Font chooseFont(int sizePx) {
        // Use the logical SansSerif font; avoid getAvailableFontFamilyNames(), which eagerly initializes
        // the native font manager (and throws on a container missing libfreetype). The caller guards
        // the actual text rasterization, which still needs native fonts when present.
        return new Font(Font.SANS_SERIF, Font.BOLD, sizePx);
    }
}
