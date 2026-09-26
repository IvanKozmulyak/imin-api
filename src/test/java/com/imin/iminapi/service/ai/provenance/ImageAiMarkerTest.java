package com.imin.iminapi.service.ai.provenance;

import com.imin.iminapi.service.ai.provenance.ImageAiMarker.SourceType;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ImageAiMarkerTest {

    /** A real 4x4 PNG as ImageIO writes it (no XMP). */
    public static byte[] png() {
        return encode("PNG");
    }

    public static byte[] jpeg() {
        return encode("JPEG");
    }

    /** A format ImageIO decodes but the marker cannot write into. */
    public static byte[] bmp() {
        return encode("BMP");
    }

    private static byte[] encode(String format) {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) img.setRGB(x, y, (x * 60) << 16 | (y * 60) << 8);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage decode(byte[] b) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(b));
    }

    private static int count(byte[] haystack, String needle) {
        String s = new String(haystack, StandardCharsets.ISO_8859_1);
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    @Test
    void png_carriesTheDigitalSourceType_andStillDecodesToTheSamePixels() throws IOException {
        byte[] original = png();

        byte[] marked = ImageAiMarker.mark(original, SourceType.TRAINED_ALGORITHMIC_MEDIA);

        assertThat(ImageAiMarker.readDigitalSourceType(marked))
                .isEqualTo("http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia");
        BufferedImage a = decode(original);
        BufferedImage b = decode(marked);
        assertThat(b.getRGB(0, 0, 4, 4, null, 0, 4)).isEqualTo(a.getRGB(0, 0, 4, 4, null, 0, 4));
    }

    @Test
    void png_xmpChunkSitsBeforeTheImageData() {
        byte[] marked = ImageAiMarker.mark(png(), SourceType.TRAINED_ALGORITHMIC_MEDIA);
        String s = new String(marked, StandardCharsets.ISO_8859_1);

        assertThat(s.indexOf("iTXtXML:com.adobe.xmp")).isPositive().isLessThan(s.indexOf("IDAT"));
    }

    @Test
    void png_remarkingReplacesTheOldPacketInsteadOfAddingASecond() {
        byte[] once = ImageAiMarker.mark(png(), SourceType.TRAINED_ALGORITHMIC_MEDIA);

        byte[] twice = ImageAiMarker.mark(once, SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA);

        assertThat(count(twice, "XML:com.adobe.xmp")).isEqualTo(1);
        assertThat(ImageAiMarker.readDigitalSourceType(twice))
                .isEqualTo("http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia");
    }

    @Test
    void jpeg_carriesTheDigitalSourceType_andStillDecodes() throws IOException {
        byte[] marked = ImageAiMarker.mark(jpeg(), SourceType.TRAINED_ALGORITHMIC_MEDIA);

        assertThat(ImageAiMarker.readDigitalSourceType(marked)).isEqualTo(SourceType.TRAINED_ALGORITHMIC_MEDIA.uri());
        assertThat(decode(marked).getWidth()).isEqualTo(4);
    }

    @Test
    void jpeg_keepsJfifFirst_andReplacesAnOldPacket() {
        byte[] once = ImageAiMarker.mark(jpeg(), SourceType.TRAINED_ALGORITHMIC_MEDIA);

        byte[] twice = ImageAiMarker.mark(once, SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA);

        assertThat(count(twice, "http://ns.adobe.com/xap/1.0/")).isEqualTo(1);
        assertThat(ImageAiMarker.readDigitalSourceType(twice))
                .isEqualTo(SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA.uri());
        // APP0 (JFIF) stays the first segment after SOI.
        assertThat(twice[2] & 0xFF).isEqualTo(0xFF);
        assertThat(twice[3] & 0xFF).isEqualTo(0xE0);
    }

    @Test
    void unsupportedFormat_isRefused() {
        byte[] gif = "GIF89a....".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(ImageAiMarker.supports(gif)).isFalse();
        assertThatThrownBy(() -> ImageAiMarker.mark(gif, SourceType.TRAINED_ALGORITHMIC_MEDIA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageAiMarker.mark(null, SourceType.TRAINED_ALGORITHMIC_MEDIA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supports_pngAndJpeg() {
        assertThat(ImageAiMarker.supports(png())).isTrue();
        assertThat(ImageAiMarker.supports(jpeg())).isTrue();
    }

    @Test
    void truncatedPng_isRefused() {
        byte[] p = png();
        byte[] truncated = Arrays.copyOf(p, 20);

        assertThatThrownBy(() -> ImageAiMarker.mark(truncated, SourceType.TRAINED_ALGORITHMIC_MEDIA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pngSignatureOnly_hasNoIhdr_andIsRefused() {
        byte[] sigOnly = Arrays.copyOf(png(), 8);

        assertThatThrownBy(() -> ImageAiMarker.mark(sigOnly, SourceType.TRAINED_ALGORITHMIC_MEDIA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IHDR");
    }

    @Test
    void corruptJpegSegmentLength_isRefused() {
        byte[] j = jpeg();
        j[4] = (byte) 0xFF; // APP0 length now runs past the end of the file
        j[5] = (byte) 0xFF;

        assertThatThrownBy(() -> ImageAiMarker.mark(j, SourceType.TRAINED_ALGORITHMIC_MEDIA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unmarkedImages_readAsNull() {
        assertThat(ImageAiMarker.readDigitalSourceType(png())).isNull();
        assertThat(ImageAiMarker.readDigitalSourceType(jpeg())).isNull();
        assertThat(ImageAiMarker.readDigitalSourceType(new byte[]{1, 2})).isNull();
    }

    @Test
    void png_insertedChunkCarriesAValidCrc() {
        byte[] marked = ImageAiMarker.mark(png(), SourceType.TRAINED_ALGORITHMIC_MEDIA);
        int at = new String(marked, StandardCharsets.ISO_8859_1).indexOf("iTXtXML:com.adobe.xmp");
        int len = ByteBuffer.wrap(marked, at - 4, 4).getInt();

        CRC32 crc = new CRC32();
        crc.update(marked, at, 4 + len); // chunk type + data
        int stored = ByteBuffer.wrap(marked, at + 4 + len, 4).getInt();

        assertThat(stored).isEqualTo((int) crc.getValue());
    }

    @Test
    void png_readsAPacketWithALanguageTagAndTranslatedKeyword() {
        String xmp = ImageAiMarker.xmp(SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes("XML:com.adobe.xmp".getBytes(StandardCharsets.ISO_8859_1));
        data.writeBytes(new byte[]{0, 0, 0});
        data.writeBytes("en-GB".getBytes(StandardCharsets.ISO_8859_1));
        data.write(0);
        data.writeBytes("XMP-Daten".getBytes(StandardCharsets.UTF_8));
        data.write(0);
        data.writeBytes(xmp.getBytes(StandardCharsets.UTF_8));

        byte[] withForeignPacket = insertAfterIhdr(png(), "iTXt", data.toByteArray());

        assertThat(ImageAiMarker.readPngXmp(withForeignPacket)).isEqualTo(xmp);
    }

    @Test
    void png_readsBackExactlyThePacketItWrote() {
        byte[] marked = ImageAiMarker.mark(png(), SourceType.TRAINED_ALGORITHMIC_MEDIA);

        assertThat(ImageAiMarker.readPngXmp(marked)).isEqualTo(ImageAiMarker.xmp(SourceType.TRAINED_ALGORITHMIC_MEDIA));
    }

    private static byte[] insertAfterIhdr(byte[] png, String type, byte[] chunkData) {
        int ihdrEnd = 8 + 12 + ByteBuffer.wrap(png, 8, 4).getInt();
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(chunkData);
        ByteBuffer chunk = ByteBuffer.allocate(12 + chunkData.length)
                .putInt(chunkData.length).put(typeBytes).put(chunkData).putInt((int) crc.getValue());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, ihdrEnd);
        out.writeBytes(chunk.array());
        out.write(png, ihdrEnd, png.length - ihdrEnd);
        return out.toByteArray();
    }
}
