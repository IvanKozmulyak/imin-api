package com.imin.iminapi.audience;

import com.imin.iminapi.audience.service.CsvContactParser;
import com.imin.iminapi.audience.service.CsvContactParser.RawContact;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Pure parser tests: varied headers, missing email column, quoted fields, BOM, phone, caps.
 */
class CsvContactParserTest {

    private static List<RawContact> parse(String csv) {
        return CsvContactParser.parse(csv.getBytes(StandardCharsets.UTF_8), 10_000);
    }

    @Test
    void simple_email_only_header() {
        List<RawContact> rows = parse("email\nalice@example.com\nbob@example.com\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).rawEmail()).isEqualTo("alice@example.com");
        assertThat(rows.get(0).rowNumber()).isEqualTo(2); // header is line 1
        assertThat(rows.get(1).rawEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void case_insensitive_email_header_variants() {
        assertThat(parse("E-Mail\nx@y.com\n").get(0).rawEmail()).isEqualTo("x@y.com");
        assertThat(parse("Email Address\nx@y.com\n").get(0).rawEmail()).isEqualTo("x@y.com");
        assertThat(parse("  EMAIL  \nx@y.com\n").get(0).rawEmail()).isEqualTo("x@y.com");
    }

    @Test
    void full_name_and_phone_columns() {
        List<RawContact> rows = parse("Full Name,Email,Mobile\nAlice A,alice@example.com,+15551234567\n");
        assertThat(rows.get(0).name()).isEqualTo("Alice A");
        assertThat(rows.get(0).rawEmail()).isEqualTo("alice@example.com");
        assertThat(rows.get(0).rawPhone()).isEqualTo("+15551234567");
    }

    @Test
    void first_and_last_name_combined() {
        List<RawContact> rows = parse("First Name,Last Name,email\nAlice,Anderson,alice@example.com\n");
        assertThat(rows.get(0).name()).isEqualTo("Alice Anderson");
    }

    @Test
    void quoted_fields_with_commas_and_escaped_quotes_and_newline() {
        String csv = "name,email\n"
                + "\"Smith, \"\"DJ\"\" Joe\",joe@example.com\n"
                + "\"Line1\nLine2\",multi@example.com\n";
        List<RawContact> rows = parse(csv);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).name()).isEqualTo("Smith, \"DJ\" Joe");
        assertThat(rows.get(0).rawEmail()).isEqualTo("joe@example.com");
        assertThat(rows.get(1).name()).isEqualTo("Line1\nLine2");
        assertThat(rows.get(1).rawEmail()).isEqualTo("multi@example.com");
    }

    @Test
    void utf8_bom_is_stripped_from_header() {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        String rest = "email\nalice@example.com\n";
        byte[] restBytes = rest.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + restBytes.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(restBytes, 0, all, bom.length, restBytes.length);

        List<RawContact> rows = CsvContactParser.parse(all, 10_000);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rawEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void missing_email_column_throws_400() {
        assertThatThrownBy(() -> parse("name,phone\nAlice,+15551234567\n"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.IMPORT_EMAIL_COLUMN_MISSING));
    }

    @Test
    void empty_file_throws_400() {
        assertThatThrownBy(() -> CsvContactParser.parse(new byte[0], 10_000))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.IMPORT_FILE_REQUIRED));
    }

    @Test
    void row_cap_exceeded_throws_400() {
        StringBuilder sb = new StringBuilder("email\n");
        for (int i = 0; i < 6; i++) sb.append("u").append(i).append("@x.com\n");
        assertThatThrownBy(() -> CsvContactParser.parse(sb.toString().getBytes(StandardCharsets.UTF_8), 5))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.IMPORT_TOO_MANY_ROWS));
    }

    @Test
    void trailing_blank_line_is_not_a_row() {
        List<RawContact> rows = parse("email\nalice@example.com\n\n");
        assertThat(rows).hasSize(1);
    }

    @Test
    void no_trailing_newline_still_parses_last_row() {
        List<RawContact> rows = parse("email\nalice@example.com");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rawEmail()).isEqualTo("alice@example.com");
    }
}
