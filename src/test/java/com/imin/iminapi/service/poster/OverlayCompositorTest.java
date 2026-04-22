package com.imin.iminapi.service.poster;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class OverlayCompositorTest {

    private final OverlayCompositor compositor = new OverlayCompositor(120, 80);

    @Test
    void applyOverlays_outputsDecodableQrCode() throws IOException, NotFoundException, ChecksumException, FormatException {
        byte[] base = solidPng(900, 1200, new Color(30, 30, 30));
        byte[] result = compositor.applyOverlays(new OverlayCompositor.Input(
                base,
                "https://imin.wtf/e/abc123",
                "Kreuzberg 12, Berlin"));

        BufferedImage composed = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(composed).isNotNull();
        assertThat(composed.getWidth()).isEqualTo(900);
        assertThat(composed.getHeight()).isEqualTo(1200);

        Result qr = decodeQrFromLowerRight(composed);
        assertThat(qr.getText()).isEqualTo("https://imin.wtf/e/abc123");
    }

    @Test
    void applyOverlays_returnsDecodableImageWhenNoOverlays() throws IOException {
        byte[] base = solidPng(200, 200, Color.BLUE);
        byte[] result = compositor.applyOverlays(new OverlayCompositor.Input(base, null, null));

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(200);
    }

    @Test
    void applyOverlays_returnsSameByteArrayWhenNoOverlays() throws IOException {
        byte[] base = solidPng(200, 200, Color.RED);
        byte[] resultBothNull  = compositor.applyOverlays(new OverlayCompositor.Input(base, null, null));
        byte[] resultBothBlank = compositor.applyOverlays(new OverlayCompositor.Input(base, "", "   "));

        assertThat(resultBothNull).isSameAs(base);
        assertThat(resultBothBlank).isSameAs(base);
    }

    @Test
    void applyOverlays_returnsNewArrayWhenAtLeastOneOverlayProvided() throws IOException {
        byte[] base = solidPng(200, 200, Color.GREEN);
        byte[] qrOnly      = compositor.applyOverlays(new OverlayCompositor.Input(base, "https://x", null));
        byte[] addressOnly = compositor.applyOverlays(new OverlayCompositor.Input(base, null, "Some address"));

        assertThat(qrOnly).isNotSameAs(base);
        assertThat(addressOnly).isNotSameAs(base);
    }

    @Test
    void renderQr_roundTripsPayload() throws NotFoundException, ChecksumException, FormatException {
        BufferedImage qr = compositor.renderQr("hello", 200);
        LuminanceSource src = new BufferedImageLuminanceSource(qr);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(src));
        Result r = new QRCodeReader().decode(bitmap);
        assertThat(r.getText()).isEqualTo("hello");
    }

    private byte[] solidPng(int w, int h, Color c) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(c);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private Result decodeQrFromLowerRight(BufferedImage composed) throws NotFoundException, ChecksumException, FormatException {
        int subW = Math.min(400, composed.getWidth());
        int subH = Math.min(400, composed.getHeight() / 2);
        int x = composed.getWidth() - subW;
        int y = composed.getHeight() - subH - Math.max(60, composed.getHeight() / 14);
        BufferedImage sub = composed.getSubimage(Math.max(0, x), Math.max(0, y), subW, subH);
        LuminanceSource src = new BufferedImageLuminanceSource(sub);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(src));
        return new QRCodeReader().decode(bitmap);
    }
}
