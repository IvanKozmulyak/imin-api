package com.imin.iminapi.audienceplan.opendata;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Streams the rows of one named sheet of an .xlsx file with the JDK only (zip + StAX).
 * Handles shared, inline and plain cell values; formulas are read as their cached value.
 */
public final class XlsxSheetReader {

    static final long MAX_PART_BYTES = 16L * 1024 * 1024;
    static final long MAX_SHEET_BYTES = 256L * 1024 * 1024;
    /** Last column Excel allows (XFD), zero-based. */
    static final int MAX_COLUMN = 16_383;

    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private XlsxSheetReader() {}

    /** Calls {@code rowHandler} once per row with cell texts at their column index (gaps are null). */
    public static void readSheet(byte[] xlsx, String sheetName, Consumer<List<String>> rowHandler) {
        readSheet(xlsx, sheetName, rowHandler, MAX_PART_BYTES, MAX_SHEET_BYTES);
    }

    static void readSheet(byte[] xlsx, String sheetName, Consumer<List<String>> rowHandler,
                          long maxPartBytes, long maxSheetBytes) {
        try {
            Map<String, byte[]> parts = readParts(xlsx,
                    List.of("xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/sharedStrings.xml"), maxPartBytes);
            byte[] workbook = parts.get("xl/workbook.xml");
            byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
            if (workbook == null || rels == null) throw new OpenDataFetchException("Not an xlsx workbook");
            String relId = sheetRelId(workbook, sheetName);
            if (relId == null) throw new OpenDataFetchException("Sheet not found: " + sheetName);
            String sheetPath = relTarget(rels, relId);
            if (sheetPath == null) throw new OpenDataFetchException("Sheet part not found: " + sheetName);
            List<String> shared = parts.containsKey("xl/sharedStrings.xml")
                    ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
            streamSheet(xlsx, sheetPath, shared, rowHandler, maxSheetBytes);
        } catch (IOException | XMLStreamException e) {
            throw new OpenDataFetchException("Unreadable xlsx", e);
        }
    }

    private static Map<String, byte[]> readParts(byte[] xlsx, List<String> names, long maxPartBytes)
            throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (names.contains(entry.getName())) {
                    out.put(entry.getName(), new LimitedInputStream(zip, maxPartBytes).readAllBytes());
                }
            }
        }
        return out;
    }

    private static String sheetRelId(byte[] workbook, String sheetName) throws XMLStreamException {
        XMLStreamReader r = reader(new ByteArrayInputStream(workbook));
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(r.getLocalName())
                        && sheetName.equals(r.getAttributeValue(null, "name"))) {
                    return r.getAttributeValue(REL_NS, "id");
                }
            }
            return null;
        } finally {
            r.close();
        }
    }

    private static String relTarget(byte[] rels, String relId) throws XMLStreamException {
        XMLStreamReader r = reader(new ByteArrayInputStream(rels));
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())
                        && relId.equals(r.getAttributeValue(null, "Id"))) {
                    String target = r.getAttributeValue(null, "Target");
                    if (target == null) return null;
                    return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                }
            }
            return null;
        } finally {
            r.close();
        }
    }

    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = reader(new ByteArrayInputStream(xml));
        try {
            StringBuilder current = null;
            int phonetic = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "si" -> current = new StringBuilder();
                        case "rPh" -> phonetic++;
                        case "t" -> {
                            String text = r.getElementText();
                            if (current != null && phonetic == 0) current.append(text);
                        }
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if ("si".equals(r.getLocalName()) && current != null) {
                        out.add(current.toString());
                        current = null;
                    } else if ("rPh".equals(r.getLocalName())) {
                        phonetic--;
                    }
                }
            }
            return out;
        } finally {
            r.close();
        }
    }

    private static void streamSheet(byte[] xlsx, String sheetPath, List<String> shared,
                                    Consumer<List<String>> rowHandler, long maxSheetBytes)
            throws IOException, XMLStreamException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!sheetPath.equals(entry.getName())) continue;
                XMLStreamReader r = reader(new LimitedInputStream(zip, maxSheetBytes));
                try {
                    readRows(r, shared, rowHandler);
                } finally {
                    r.close();
                }
                return;
            }
        }
        throw new OpenDataFetchException("Sheet part missing from archive: " + sheetPath);
    }

    private static void readRows(XMLStreamReader r, List<String> shared, Consumer<List<String>> rowHandler)
            throws XMLStreamException {
        List<String> row = null;
        int col = -1;
        String type = null;
        String value = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> { row = new ArrayList<>(); col = -1; }
                    case "c" -> {
                        String ref = r.getAttributeValue(null, "r");
                        col = ref == null ? col + 1 : columnIndex(ref);
                        if (col > MAX_COLUMN) throw new OpenDataFetchException("Cell beyond column XFD");
                        type = r.getAttributeValue(null, "t");
                        value = null;
                    }
                    case "v" -> value = r.getElementText();
                    case "t" -> value = (value == null ? "" : value) + r.getElementText();
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if ("c".equals(r.getLocalName()) && row != null) {
                    while (row.size() <= col) row.add(null);
                    row.set(col, cellText(type, value, shared));
                } else if ("row".equals(r.getLocalName()) && row != null) {
                    rowHandler.accept(row);
                    row = null;
                }
            }
        }
    }

    private static String cellText(String type, String value, List<String> shared) {
        if (value == null) return null;
        if ("s".equals(type)) {
            int idx;
            try {
                idx = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new OpenDataFetchException("Bad shared-string index", e);
            }
            if (idx < 0 || idx >= shared.size()) throw new OpenDataFetchException("Shared-string index out of range");
            return shared.get(idx);
        }
        return value;
    }

    /** "A1" → 0, "H12" → 7, "AA3" → 26; past XFD fails before the index can overflow. */
    static int columnIndex(String ref) {
        int col = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            if (col > MAX_COLUMN + 1) throw new OpenDataFetchException("Bad cell reference: " + ref);
            i++;
        }
        if (col == 0) throw new OpenDataFetchException("Bad cell reference: " + ref);
        return col - 1;
    }

    private static XMLStreamReader reader(InputStream in) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        try {
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
            // not every StAX implementation knows the property; DTDs are already off
        }
        return f.createXMLStreamReader(in, "UTF-8");
    }

    /** Fails once more than {@code max} bytes were read, so a zip bomb cannot run unbounded. */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private long count;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) add(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) add(n);
            return n;
        }

        @Override
        public void close() {
            // the zip stream is closed by its owner
        }

        private void add(long n) throws IOException {
            count += n;
            if (count > max) throw new IOException("xlsx part larger than " + max + " bytes");
        }
    }
}
