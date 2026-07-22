package com.imin.iminapi.audience.service;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Minimal RFC4180 CSV parser for the contact-import surface.
 *
 * <p>Handles: a UTF-8 BOM, quoted fields, embedded commas / double-quotes ({@code ""})
 * / newlines inside quotes, and both {@code \r\n} and {@code \n} line endings.
 *
 * <p>Column detection is header-driven and case-insensitive (headers are trimmed +
 * lower-cased before matching):
 * <ul>
 *   <li><b>email</b> (required): {@code email}, {@code e-mail}, {@code email address}, {@code emailaddress}</li>
 *   <li><b>name</b> (optional): {@code name} / {@code full name} / {@code fullname}, or
 *       {@code first name} + {@code last name} combined</li>
 *   <li><b>phone</b> (optional): {@code phone}, {@code mobile}, {@code phone number}, {@code telephone}, …</li>
 * </ul>
 *
 * <p>Never rejects a row for a bad phone or missing name — only the email column being
 * absent, or exceeding the row cap, aborts the whole file (400).
 */
public final class CsvContactParser {

    private static final Set<String> EMAIL_HEADERS =
            Set.of("email", "e-mail", "email address", "emailaddress", "e-mail address");
    private static final Set<String> FULL_NAME_HEADERS =
            Set.of("name", "full name", "fullname", "full_name");
    private static final Set<String> FIRST_NAME_HEADERS =
            Set.of("first name", "firstname", "first_name", "first", "given name");
    private static final Set<String> LAST_NAME_HEADERS =
            Set.of("last name", "lastname", "last_name", "last", "surname", "family name");
    private static final Set<String> PHONE_HEADERS =
            Set.of("phone", "mobile", "phone number", "phonenumber", "phone_number",
                   "telephone", "mobile number", "cell", "tel");

    private CsvContactParser() {}

    /** A single data row, pre-mapped to the columns we care about. Values are raw (untrimmed email). */
    public record RawContact(int rowNumber, String rawEmail, String name, String rawPhone) {}

    /**
     * Parse the uploaded bytes into raw contacts.
     *
     * @param bytes   raw file bytes (UTF-8, optional BOM)
     * @param maxRows hard cap on data rows; exceeding it throws 400 IMPORT_TOO_MANY_ROWS
     * @throws ApiException 400 if the file is empty, unparseable, missing an email column,
     *                      or over the row cap
     */
    public static List<RawContact> parse(byte[] bytes, int maxRows) {
        if (bytes == null || bytes.length == 0) {
            throw badRequest(ErrorCode.IMPORT_FILE_REQUIRED, "CSV file is empty");
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1); // strip UTF-8 BOM
        }

        List<List<String>> rows = tokenize(content, maxRows);
        if (rows.isEmpty()) {
            throw badRequest(ErrorCode.IMPORT_FILE_REQUIRED, "CSV file has no rows");
        }

        List<String> header = rows.get(0);
        int emailIdx = -1, fullNameIdx = -1, firstNameIdx = -1, lastNameIdx = -1, phoneIdx = -1;
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim().toLowerCase(java.util.Locale.ROOT);
            if (emailIdx < 0 && EMAIL_HEADERS.contains(h)) emailIdx = i;
            else if (fullNameIdx < 0 && FULL_NAME_HEADERS.contains(h)) fullNameIdx = i;
            else if (firstNameIdx < 0 && FIRST_NAME_HEADERS.contains(h)) firstNameIdx = i;
            else if (lastNameIdx < 0 && LAST_NAME_HEADERS.contains(h)) lastNameIdx = i;
            else if (phoneIdx < 0 && PHONE_HEADERS.contains(h)) phoneIdx = i;
        }
        if (emailIdx < 0) {
            throw badRequest(ErrorCode.IMPORT_EMAIL_COLUMN_MISSING,
                    "CSV is missing a required 'email' column");
        }

        List<RawContact> out = new ArrayList<>(rows.size() - 1);
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String email = cell(row, emailIdx);
            String name = cell(row, fullNameIdx);
            if (name.isBlank()) {
                String first = cell(row, firstNameIdx);
                String last = cell(row, lastNameIdx);
                name = (first + " " + last).trim();
            }
            String phone = cell(row, phoneIdx);
            // file line number: header is line 1, first data row is line 2
            out.add(new RawContact(r + 1, email, name.isBlank() ? null : name.trim(),
                    phone.isBlank() ? null : phone));
        }
        return out;
    }

    private static String cell(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) return "";
        String v = row.get(idx);
        return v == null ? "" : v;
    }

    /** RFC4180 tokenizer. Skips a trailing empty line. Enforces the row cap on DATA rows. */
    private static List<List<String>> tokenize(String s, int maxRows) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int n = s.length();

        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i++; // consume the escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                switch (c) {
                    case '"' -> inQuotes = true;
                    case ',' -> { current.add(field.toString()); field.setLength(0); }
                    case '\r' -> { /* swallow; handled by the following \n or EOL */ }
                    case '\n' -> {
                        current.add(field.toString());
                        field.setLength(0);
                        rows.add(current);
                        current = new ArrayList<>();
                        checkRowCap(rows, maxRows);
                    }
                    default -> field.append(c);
                }
            }
        }
        // flush the last field/row if the file did not end with a newline
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        // drop a trailing all-empty row (e.g. file ended with a newline then EOF handled above,
        // or a stray blank final line)
        if (!rows.isEmpty()) {
            List<String> lastRow = rows.get(rows.size() - 1);
            if (lastRow.size() == 1 && lastRow.get(0).isBlank()) {
                rows.remove(rows.size() - 1);
            }
        }
        checkRowCap(rows, maxRows);
        return rows;
    }

    private static void checkRowCap(List<List<String>> rows, int maxRows) {
        // rows includes the header; data rows = rows - 1
        if (rows.size() - 1 > maxRows) {
            throw badRequest(ErrorCode.IMPORT_TOO_MANY_ROWS,
                    "CSV exceeds the maximum of " + maxRows + " rows");
        }
    }

    private static ApiException badRequest(ErrorCode code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
