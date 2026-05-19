package com.imin.iminapi.service.ticket;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrImageRendererTest {

    @Test
    void renders_decodable_png_at_requested_size() throws Exception {
        QrImageRenderer r = new QrImageRenderer();
        byte[] png = r.render("imin1.abc.xyz", 320);
        assertNotNull(png);
        assertTrue(png.length > 100, "png suspiciously small");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertEquals(320, img.getWidth());
        assertEquals(320, img.getHeight());
    }

    @Test
    void rejects_empty_payload() {
        QrImageRenderer r = new QrImageRenderer();
        assertThrows(IllegalArgumentException.class, () -> r.render("", 320));
        assertThrows(IllegalArgumentException.class, () -> r.render(null, 320));
    }
}
