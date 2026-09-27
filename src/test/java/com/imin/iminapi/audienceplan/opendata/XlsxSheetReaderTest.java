package com.imin.iminapi.audienceplan.opendata;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XlsxSheetReaderTest {

    @Test
    void readsSharedAndNumericCellsOfTheNamedSheetOnly() {
        byte[] xlsx = new XlsxBuilder()
                .sheet("Other", List.of(List.of("wrong")))
                .sheet("Data", List.of(List.of("a", 1), List.of("b", 2)))
                .build();

        assertThat(rows(xlsx, "Data")).containsExactly(List.of("a", "1"), List.of("b", "2"));
    }

    @Test
    void inlineStringsAndRichRunsAreRead() {
        byte[] xlsx = new XlsxBuilder().rawSheet("Data",
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>plain</t></is></c>"
                        + "<c r=\"B1\" t=\"inlineStr\"><is><r><t>ri</t></r><r><t>ch</t></r></is></c></row>").build();

        assertThat(rows(xlsx, "Data")).containsExactly(List.of("plain", "rich"));
    }

    @Test
    void aSkippedCellKeepsTheColumnPositionsOfTheOthers() {
        byte[] xlsx = new XlsxBuilder().rawSheet("Data",
                "<row r=\"1\"><c r=\"A1\"><v>1</v></c><c r=\"C1\"><v>3</v></c></row>").build();

        assertThat(rows(xlsx, "Data")).containsExactly(Arrays.asList("1", null, "3"));
    }

    @Test
    void cellsWithoutAReferenceFollowTheirNeighbour() {
        byte[] xlsx = new XlsxBuilder().rawSheet("Data", "<row><c><v>1</v></c><c><v>2</v></c></row>").build();

        assertThat(rows(xlsx, "Data")).containsExactly(List.of("1", "2"));
    }

    @Test
    void anEmptyCellIsNull() {
        byte[] xlsx = new XlsxBuilder().rawSheet("Data", "<row r=\"1\"><c r=\"A1\"/><c r=\"B1\"><v>2</v></c></row>").build();

        assertThat(rows(xlsx, "Data")).containsExactly(Arrays.asList(null, "2"));
    }

    @Test
    void aMissingSheetFails() {
        byte[] xlsx = new XlsxBuilder().sheet("Data", List.of(List.of("a"))).build();

        assertThatThrownBy(() -> rows(xlsx, "Nope")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Sheet not found");
    }

    @Test
    void anOutOfRangeSharedStringFails() {
        byte[] xlsx = new XlsxBuilder().rawSheet("Data", "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>9</v></c></row>").build();

        assertThatThrownBy(() -> rows(xlsx, "Data")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void aNonNumericSharedStringIndexFails() {
        byte[] xlsx = new XlsxBuilder().rawSheet("Data", "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>x</v></c></row>").build();

        assertThatThrownBy(() -> rows(xlsx, "Data")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Bad shared-string index");
    }

    @Test
    void somethingThatIsNotAWorkbookFails() {
        assertThatThrownBy(() -> rows("not a zip".getBytes(), "Data")).isInstanceOf(OpenDataFetchException.class);
    }

    @Test
    void columnIndexReadsLetters() {
        assertThat(XlsxSheetReader.columnIndex("A1")).isZero();
        assertThat(XlsxSheetReader.columnIndex("H12")).isEqualTo(7);
        assertThat(XlsxSheetReader.columnIndex("AA3")).isEqualTo(26);
        assertThatThrownBy(() -> XlsxSheetReader.columnIndex("12")).isInstanceOf(OpenDataFetchException.class);
    }

    private static List<List<String>> rows(byte[] xlsx, String sheet) {
        List<List<String>> out = new ArrayList<>();
        XlsxSheetReader.readSheet(xlsx, sheet, out::add);
        return out;
    }

    private static final String WB = "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
    private static final String SHEET = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row></sheetData></worksheet>";

    private static String rels(String target) {
        return "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Target=\"" + target + "\"/></Relationships>";
    }

    private static String sst(String si) {
        return "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" + si + "</sst>";
    }

    @Test
    void anAbsoluteRelationshipTargetIsFollowed() {
        byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB,
                "xl/_rels/workbook.xml.rels", rels("/xl/worksheets/s.xml"),
                "xl/sharedStrings.xml", sst("<si><t>ok</t></si>"), "xl/worksheets/s.xml", SHEET));

        assertThat(rows(xlsx, "Data")).containsExactly(List.of("ok"));
    }

    @Test
    void phoneticRunsAreNotPartOfTheText() {
        byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB,
                "xl/_rels/workbook.xml.rels", rels("worksheets/s.xml"),
                "xl/sharedStrings.xml", sst("<si><r><t>Me</t></r><r><t>tz</t></r><rPh><t>メッツ</t></rPh></si>"),
                "xl/worksheets/s.xml", SHEET));

        assertThat(rows(xlsx, "Data")).containsExactly(List.of("Metz"));
    }

    @Test
    void aWorkbookWithoutSharedStringsReadsPlainCells() {
        byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB,
                "xl/_rels/workbook.xml.rels", rels("worksheets/s.xml"),
                "xl/worksheets/s.xml", SHEET.replace(" t=\"s\"", "")));

        assertThat(rows(xlsx, "Data")).containsExactly(List.of("0"));
    }

    @Test
    void aSheetWithoutARelationshipFails() {
        byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB,
                "xl/_rels/workbook.xml.rels", rels("worksheets/s.xml").replace("rId1", "rId9")));

        assertThatThrownBy(() -> rows(xlsx, "Data")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Sheet part not found");
    }

    @Test
    void aRelationshipWithoutATargetFails() {
        byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB, "xl/_rels/workbook.xml.rels",
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\"/></Relationships>"));

        assertThatThrownBy(() -> rows(xlsx, "Data")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Sheet part not found");
    }

    @Test
    void aRelationshipToAMissingPartFails() {
        byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB,
                "xl/_rels/workbook.xml.rels", rels("worksheets/gone.xml")));

        assertThatThrownBy(() -> rows(xlsx, "Data")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("missing from archive");
    }

    @Test
    void anOversizePartFails() {
        String huge = "<si><t>" + "x".repeat((int) XlsxSheetReader.MAX_PART_BYTES) + "</t></si>";
        byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB,
                "xl/_rels/workbook.xml.rels", rels("worksheets/s.xml"),
                "xl/sharedStrings.xml", sst(huge), "xl/worksheets/s.xml", SHEET));

        assertThatThrownBy(() -> rows(xlsx, "Data")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Unreadable xlsx");
    }

    @Test
    void columnIndexStopsAtXfd() {
        assertThat(XlsxSheetReader.columnIndex("XFD1")).isEqualTo(16_383);
        assertThatThrownBy(() -> XlsxSheetReader.columnIndex("XFE1")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Bad cell reference");
    }

    @Test
    void aColumnReferenceThatWouldOverflowAnIntFails() {
        // 26^7 > Integer.MAX_VALUE: without the cap this wraps to a negative or bogus index
        assertThatThrownBy(() -> XlsxSheetReader.columnIndex("ZZZZZZZZZZZZ1")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Bad cell reference");
    }

    @Test
    void aCellPastXfdInASheetFails() {
        byte[] xlsx = new XlsxBuilder().rawSheet("Data", "<row r=\"1\"><c r=\"XFE1\"><v>1</v></c></row>").build();

        assertThatThrownBy(() -> rows(xlsx, "Data")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Bad cell reference");
    }

    @Test
    void anUnreferencedCellFollowingXfdFails() {
        byte[] xlsx = new XlsxBuilder().rawSheet("Data",
                "<row r=\"1\"><c r=\"XFD1\"><v>1</v></c><c><v>2</v></c></row>").build();

        assertThatThrownBy(() -> rows(xlsx, "Data")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("beyond column XFD");
    }

    @Test
    void aSheetDeclaringAnInternalEntityIsRejected() {
        String sheet = "<?xml version=\"1.0\"?><!DOCTYPE worksheet [<!ENTITY boom \"INJECTED\">]>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData><row r=\"1\"><c r=\"A1\"><v>&boom;</v></c></row></sheetData></worksheet>";
        byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB,
                "xl/_rels/workbook.xml.rels", rels("worksheets/s.xml"), "xl/worksheets/s.xml", sheet));

        assertThatThrownBy(() -> rows(xlsx, "Data")).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Unreadable xlsx");
    }

    @Test
    void aSheetReferencingAnExternalEntityIsRejectedWithoutReadingIt() throws Exception {
        java.nio.file.Path secret = java.nio.file.Files.createTempFile("xxe", ".txt");
        java.nio.file.Files.writeString(secret, "SECRET");
        try {
            String sheet = "<?xml version=\"1.0\"?><!DOCTYPE worksheet [<!ENTITY xxe SYSTEM \""
                    + secret.toUri() + "\">]>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>&xxe;</t></is></c></row>"
                    + "</sheetData></worksheet>";
            byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB,
                    "xl/_rels/workbook.xml.rels", rels("worksheets/s.xml"), "xl/worksheets/s.xml", sheet));
            List<List<String>> seen = new ArrayList<>();

            assertThatThrownBy(() -> XlsxSheetReader.readSheet(xlsx, "Data", seen::add))
                    .isInstanceOf(OpenDataFetchException.class).hasMessageContaining("Unreadable xlsx");
            assertThat(seen).noneMatch(row -> row.contains("SECRET"));
        } finally {
            java.nio.file.Files.deleteIfExists(secret);
        }
    }

    @Test
    void aSheetPartOverTheSheetCapFails() {
        byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB,
                "xl/_rels/workbook.xml.rels", rels("worksheets/s.xml"),
                "xl/sharedStrings.xml", sst("<si><t>ok</t></si>"), "xl/worksheets/s.xml", SHEET));
        List<List<String>> seen = new ArrayList<>();

        assertThatThrownBy(() -> XlsxSheetReader.readSheet(xlsx, "Data", seen::add,
                XlsxSheetReader.MAX_PART_BYTES, SHEET.length() - 1L))
                .isInstanceOf(OpenDataFetchException.class).hasMessageContaining("Unreadable xlsx");
    }

    @Test
    void aSheetPartExactlyAtTheSheetCapIsRead() {
        byte[] xlsx = XlsxBuilder.zip(java.util.Map.of("xl/workbook.xml", WB,
                "xl/_rels/workbook.xml.rels", rels("worksheets/s.xml"),
                "xl/sharedStrings.xml", sst("<si><t>ok</t></si>"), "xl/worksheets/s.xml", SHEET));
        List<List<String>> seen = new ArrayList<>();

        XlsxSheetReader.readSheet(xlsx, "Data", seen::add, XlsxSheetReader.MAX_PART_BYTES, SHEET.length());

        assertThat(seen).containsExactly(List.of("ok"));
    }

    @Test
    void theDefaultCapsAreTheDocumentedOnes() {
        assertThat(XlsxSheetReader.MAX_PART_BYTES).isEqualTo(16L * 1024 * 1024);
        assertThat(XlsxSheetReader.MAX_SHEET_BYTES).isEqualTo(256L * 1024 * 1024);
    }
}
