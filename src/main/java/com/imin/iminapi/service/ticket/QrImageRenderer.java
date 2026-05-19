package com.imin.iminapi.service.ticket;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Renders a QR-coded PNG for a given payload. Uses zxing with high error
 * correction (level H) so the code still scans cleanly with a printed
 * thumbnail or a smudged camera. Two-module quiet zone keeps the QR
 * scannable when embedded in HTML/email layouts.
 */
@Component
public class QrImageRenderer {

    public byte[] render(String payload, int sizePx) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (sizePx <= 0) {
            throw new IllegalArgumentException("sizePx must be positive");
        }
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(
                    payload,
                    BarcodeFormat.QR_CODE,
                    sizePx, sizePx,
                    Map.of(
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                            EncodeHintType.MARGIN, 2));
            BufferedImage img = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < sizePx; y++) {
                for (int x = 0; x < sizePx; x++) {
                    img.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Failed to render QR PNG", e);
        }
    }
}
