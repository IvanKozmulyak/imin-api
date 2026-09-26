package com.imin.iminapi.service.ai.provenance;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Embeds the AI Act Art.50(2) marker (ADR-0005), an XMP IPTC {@code DigitalSourceType}, into PNG
 * and JPEG bytes without re-encoding, so pixels and other metadata are kept.
 */
public final class ImageAiMarker {

    /** IPTC digital-source-type terms (cv.iptc.org/newscodes/digitalsourcetype). */
    public enum SourceType {
        /** The image as the model produced it. */
        TRAINED_ALGORITHMIC_MEDIA("http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"),
        /** Model output combined with non-AI material (the brand-logo composite). */
        COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA(
                "http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia");

        private final String uri;

        SourceType(String uri) { this.uri = uri; }

        public String uri() { return uri; }
    }

    static final String XMP_PNG_KEYWORD = "XML:com.adobe.xmp";
    static final String XMP_JPEG_NS = "http://ns.adobe.com/xap/1.0/\0";

    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private ImageAiMarker() {}

    /** True when the bytes are a format {@link #mark} can write into. */
    public static boolean supports(byte[] image) {
        return isPng(image) || isJpeg(image);
    }

    /**
     * Returns a copy of {@code image} carrying the marker, replacing any XMP packet it had.
     *
     * @throws IllegalArgumentException for a format other than PNG or JPEG, or a corrupt file
     */
    public static byte[] mark(byte[] image, SourceType type) {
        if (isPng(image)) return markPng(image, xmp(type));
        if (isJpeg(image)) return markJpeg(image, xmp(type));
        throw new IllegalArgumentException("Cannot embed an AI marker: not a PNG or JPEG");
    }

    /** The DigitalSourceType URI embedded in {@code image}, or null when it carries none. */
    public static String readDigitalSourceType(byte[] image) {
        String packet = isPng(image) ? readPngXmp(image) : isJpeg(image) ? readJpegXmp(image) : null;
        if (packet == null) return null;
        String attr = "Iptc4xmpExt:DigitalSourceType=\"";
        int at = packet.indexOf(attr);
        if (at < 0) return null;
        int start = at + attr.length();
        int end = packet.indexOf('"', start);
        return end < 0 ? null : packet.substring(start, end);
    }

    static String xmp(SourceType type) {
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\""
                + " xmlns:Iptc4xmpExt=\"http://iptc.org/std/Iptc4xmpExt/2008-02-29/\""
                + " Iptc4xmpExt:DigitalSourceType=\"" + type.uri() + "\"/>"
                + "</rdf:RDF></x:xmpmeta>"
                + "<?xpacket end=\"w\"?>";
    }

    // ---- PNG ----

    private static boolean isPng(byte[] b) {
        if (b == null || b.length < PNG_SIGNATURE.length) return false;
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (b[i] != PNG_SIGNATURE[i]) return false;
        }
        return true;
    }

    private static byte[] markPng(byte[] png, String xmp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + xmp.length() + 64);
        out.write(png, 0, PNG_SIGNATURE.length);
        int pos = PNG_SIGNATURE.length;
        boolean inserted = false;
        while (pos < png.length) {
            if (pos + 12 > png.length) throw new IllegalArgumentException("Truncated PNG chunk");
            int len = readInt(png, pos);
            if (len < 0 || pos + 12L + len > png.length) {
                throw new IllegalArgumentException("Corrupt PNG chunk length");
            }
            String type = new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int chunkEnd = pos + 12 + len;
            boolean isOldXmp = "iTXt".equals(type) && startsWithKeyword(png, pos + 8, len, XMP_PNG_KEYWORD);
            if (!isOldXmp) out.write(png, pos, chunkEnd - pos);
            // XMP goes straight after IHDR, ahead of every IDAT.
            if (!inserted && "IHDR".equals(type)) {
                writeChunk(out, "iTXt", itxtXmp(xmp));
                inserted = true;
            }
            pos = chunkEnd;
            if ("IEND".equals(type)) break;
        }
        if (!inserted) throw new IllegalArgumentException("PNG has no IHDR chunk");
        return out.toByteArray();
    }

    private static byte[] itxtXmp(String xmp) {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        d.writeBytes(XMP_PNG_KEYWORD.getBytes(StandardCharsets.ISO_8859_1));
        d.write(0); // keyword terminator
        d.write(0); // compression flag: uncompressed
        d.write(0); // compression method
        d.write(0); // empty language tag
        d.write(0); // empty translated keyword
        d.writeBytes(xmp.getBytes(StandardCharsets.UTF_8));
        return d.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        writeInt(out, data.length);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    /** The XMP packet text of a PNG, or null when it carries none. */
    static String readPngXmp(byte[] png) {
        int pos = PNG_SIGNATURE.length;
        while (pos + 12 <= png.length) {
            int len = readInt(png, pos);
            if (len < 0 || pos + 12L + len > png.length) return null;
            String type = new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if ("iTXt".equals(type) && startsWithKeyword(png, pos + 8, len, XMP_PNG_KEYWORD)) {
                return itxtText(png, pos + 8, pos + 8 + len);
            }
            if ("IEND".equals(type)) return null;
            pos += 12 + len;
        }
        return null;
    }

    // iTXt: keyword NUL, compression flag, method, language tag NUL, translated keyword NUL, text.
    private static String itxtText(byte[] b, int dataStart, int dataEnd) {
        int flagAt = dataStart + XMP_PNG_KEYWORD.length() + 1;
        if (flagAt + 2 > dataEnd) return null;
        if (b[flagAt] != 0) return null; // compressed XMP is not read back
        int langEnd = indexOfNul(b, flagAt + 2, dataEnd);
        if (langEnd < 0) return null;
        int translatedEnd = indexOfNul(b, langEnd + 1, dataEnd);
        if (translatedEnd < 0) return null;
        return new String(b, translatedEnd + 1, dataEnd - translatedEnd - 1, StandardCharsets.UTF_8);
    }

    private static int indexOfNul(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] == 0) return i;
        }
        return -1;
    }

    private static boolean startsWithKeyword(byte[] b, int dataStart, int dataLen, String keyword) {
        byte[] k = keyword.getBytes(StandardCharsets.ISO_8859_1);
        if (dataLen < k.length + 1) return false;
        for (int i = 0; i < k.length; i++) {
            if (b[dataStart + i] != k[i]) return false;
        }
        return b[dataStart + k.length] == 0;
    }

    // ---- JPEG ----

    private static boolean isJpeg(byte[] b) {
        return b != null && b.length >= 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    private static byte[] markJpeg(byte[] jpg, String xmp) {
        byte[] ns = XMP_JPEG_NS.getBytes(StandardCharsets.ISO_8859_1);
        byte[] packet = xmp.getBytes(StandardCharsets.UTF_8);
        int segLen = 2 + ns.length + packet.length;
        if (segLen > 0xFFFF) throw new IllegalArgumentException("XMP packet too large for one APP1");
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpg.length + segLen + 2);
        out.write(0xFF);
        out.write(0xD8);
        // Keep leading APP0 (JFIF) / APP1 (Exif) first, as readers expect, then our XMP APP1.
        int pos = 2;
        pos = copyLeadingAppSegments(jpg, pos, out, ns);
        out.write(0xFF);
        out.write(0xE1);
        out.write((segLen >> 8) & 0xFF);
        out.write(segLen & 0xFF);
        out.writeBytes(ns);
        out.writeBytes(packet);
        // Remaining segments, minus any other XMP APP1 before the scan data.
        while (pos + 4 <= jpg.length && (jpg[pos] & 0xFF) == 0xFF) {
            int marker = jpg[pos + 1] & 0xFF;
            if (marker == 0xDA) break; // start of scan: the rest is copied verbatim
            int len = ((jpg[pos + 2] & 0xFF) << 8) | (jpg[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > jpg.length) throw new IllegalArgumentException("Corrupt JPEG segment");
            if (!(marker == 0xE1 && hasPrefix(jpg, pos + 4, ns))) out.write(jpg, pos, 2 + len);
            pos += 2 + len;
        }
        out.write(jpg, pos, jpg.length - pos);
        return out.toByteArray();
    }

    private static int copyLeadingAppSegments(byte[] jpg, int pos, ByteArrayOutputStream out, byte[] xmpNs) {
        while (pos + 4 <= jpg.length && (jpg[pos] & 0xFF) == 0xFF) {
            int marker = jpg[pos + 1] & 0xFF;
            boolean leading = marker == 0xE0 || (marker == 0xE1 && !hasPrefix(jpg, pos + 4, xmpNs));
            if (!leading) break;
            int len = ((jpg[pos + 2] & 0xFF) << 8) | (jpg[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > jpg.length) throw new IllegalArgumentException("Corrupt JPEG segment");
            out.write(jpg, pos, 2 + len);
            pos += 2 + len;
        }
        return pos;
    }

    private static String readJpegXmp(byte[] jpg) {
        byte[] ns = XMP_JPEG_NS.getBytes(StandardCharsets.ISO_8859_1);
        int pos = 2;
        while (pos + 4 <= jpg.length && (jpg[pos] & 0xFF) == 0xFF) {
            int marker = jpg[pos + 1] & 0xFF;
            if (marker == 0xDA) return null;
            int len = ((jpg[pos + 2] & 0xFF) << 8) | (jpg[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > jpg.length) return null;
            if (marker == 0xE1 && hasPrefix(jpg, pos + 4, ns)) {
                int start = pos + 4 + ns.length;
                return new String(jpg, start, pos + 2 + len - start, StandardCharsets.UTF_8);
            }
            pos += 2 + len;
        }
        return null;
    }

    private static boolean hasPrefix(byte[] b, int at, byte[] prefix) {
        if (at + prefix.length > b.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (b[at + i] != prefix[i]) return false;
        }
        return true;
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}
