package com.imin.iminapi.audienceplan.opendata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Minimal .xlsx writer for edge cases the recorded IGSS file does not have. */
final class XlsxBuilder {

    private final List<String> sheetNames = new ArrayList<>();
    private final List<String> sheetXml = new ArrayList<>();
    private final List<String> shared = new ArrayList<>();

    /** Strings go to shared strings, numbers stay plain. */
    XlsxBuilder sheet(String name, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sb.append("<row r=\"").append(r + 1).append("\">");
            List<Object> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                Object v = row.get(c);
                if (v == null) continue;
                String ref = (char) ('A' + c) + String.valueOf(r + 1);
                if (v instanceof Number) {
                    sb.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
                } else {
                    shared.add(v.toString());
                    sb.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(shared.size() - 1).append("</v></c>");
                }
            }
            sb.append("</row>");
        }
        sheetNames.add(name);
        sheetXml.add(sb.append("</sheetData></worksheet>").toString());
        return this;
    }

    /** A sheet written verbatim, for inline strings and odd cells. */
    XlsxBuilder rawSheet(String name, String sheetDataInner) {
        sheetNames.add(name);
        sheetXml.add("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                + sheetDataInner + "</sheetData></worksheet>");
        return this;
    }

    byte[] build() {
        StringBuilder wb = new StringBuilder("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels = new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheetNames.size(); i++) {
            wb.append("<sheet name=\"").append(sheetNames.get(i)).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i + 1).append("\" Target=\"worksheets/sheet").append(i + 1)
                    .append(".xml\" Type=\"worksheet\"/>");
        }
        wb.append("</sheets></workbook>");
        rels.append("</Relationships>");
        StringBuilder sst = new StringBuilder("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        for (String s : shared) sst.append("<si><t>").append(s).append("</t></si>");
        sst.append("</sst>");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < sheetXml.size(); i++) put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml.get(i));
            put(zip, "xl/workbook.xml", wb.toString());
            put(zip, "xl/_rels/workbook.xml.rels", rels.toString());
            put(zip, "xl/sharedStrings.xml", sst.toString());
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An archive with exactly these parts, for broken-workbook cases. */
    static byte[] zip(java.util.Map<String, String> parts) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (java.util.Map.Entry<String, String> e : parts.entrySet()) put(zip, e.getKey(), e.getValue());
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
