package com.imin.iminapi.audienceplan.opendata;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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

    static Stream<Arguments> malformedWorkbooks() {
        String relsNoTarget = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\"/></Relationships>";
        return Stream.of(
                arguments("missing sheet", (ThrowingCallable) () -> rows(
                        new XlsxBuilder().sheet("Data", List.of(List.of("a"))).build(), "Nope"),
                        "Sheet not found"),
                arguments("out-of-range shared string", (ThrowingCallable) () -> rows(new XlsxBuilder().rawSheet("Data",
                        "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>9</v></c></row>").build(), "Data"), "out of range"),
                arguments("non-numeric shared string index", (ThrowingCallable) () -> rows(new XlsxBuilder().rawSheet("Data",
                        "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>x</v></c></row>").build(), "Data"),
                        "Bad shared-string index"),
                arguments("not a workbook", (ThrowingCallable) () -> rows("not a zip".getBytes(), "Data"), ""),
                arguments("sheet without a relationship", (ThrowingCallable) () -> rows(XlsxBuilder.zip(java.util.Map.of(
                        "xl/workbook.xml", WB, "xl/_rels/workbook.xml.rels",
                        rels("worksheets/s.xml").replace("rId1", "rId9"))), "Data"), "Sheet part not found"),
                arguments("relationship without a target", (ThrowingCallable) () -> rows(XlsxBuilder.zip(java.util.Map.of(
                        "xl/workbook.xml", WB, "xl/_rels/workbook.xml.rels", relsNoTarget)), "Data"),
                        "Sheet part not found"),
                arguments("relationship to a missing part", (ThrowingCallable) () -> rows(XlsxBuilder.zip(java.util.Map.of(
                        "xl/workbook.xml", WB, "xl/_rels/workbook.xml.rels", rels("worksheets/gone.xml"))), "Data"),
                        "missing from archive"),
                // 26^7 > Integer.MAX_VALUE: without the cap this wraps to a negative or bogus index
                arguments("column reference that overflows an int",
                        (ThrowingCallable) () -> XlsxSheetReader.columnIndex("ZZZZZZZZZZZZ1"), "Bad cell reference"),
                arguments("cell past XFD", (ThrowingCallable) () -> rows(new XlsxBuilder().rawSheet("Data",
                        "<row r=\"1\"><c r=\"XFE1\"><v>1</v></c></row>").build(), "Data"), "Bad cell reference"),
                arguments("unreferenced cell following XFD", (ThrowingCallable) () -> rows(new XlsxBuilder().rawSheet("Data",
                        "<row r=\"1\"><c r=\"XFD1\"><v>1</v></c><c><v>2</v></c></row>").build(), "Data"),
                        "beyond column XFD"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedWorkbooks")
    void malformedWorkbook_failsClosed(String name, ThrowingCallable read, String message) {
        var thrown = assertThatThrownBy(read).isInstanceOf(OpenDataFetchException.class);
        if (!message.isEmpty()) {
            thrown.hasMessageContaining(message);
        }
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
