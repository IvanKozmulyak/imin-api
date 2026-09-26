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
 *   <li><b>provenance</b> (optional): {@code source_platform}, {@code export_date}, {@code events},
 *       {@code last_purchase_date}, {@code marketing_status}, {@code proof_ref} — the names the
 *       dashboard's column mapper writes</li>
 * </ul>
 *
 * <p>Never rejects a row for a bad phone or missing name. The whole file is refused (400) when
 * the email column is absent, the row cap is exceeded, or a header names data that must never
 * be imported (ID numbers, payment data, IP addresses, health data).
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

    static final String SOURCE_PLATFORM = "source_platform";
    static final String EXPORT_DATE = "export_date";
    static final String EVENTS = "events";
    static final String LAST_PURCHASE_DATE = "last_purchase_date";
    static final String MARKETING_STATUS = "marketing_status";
    static final String PROOF_REF = "proof_ref";

    /** Header words that alone mark a forbidden column. */
    private static final Set<String> FORBIDDEN_WORDS = Set.of(
            "passport", "ssn", "nir", "dni", "nie",
            "iban", "cvv", "cvc", "pan",
            "ip", "ipv4", "ipv6", "ipaddress",
            "health", "medical", "allergy", "allergies", "disability", "diagnosis");
    /** Word pairs that mark a forbidden column wherever they appear in the header. */
    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "id number", "national id", "identity number", "id card", "social security", "tax id",
            "card number", "credit card", "debit card", "bank account",
            "ip address");

    private CsvContactParser() {}

    /** A single data row, pre-mapped to the columns we care about. Values are raw (untrimmed email). */
    public record RawContact(int rowNumber, String rawEmail, String name, String rawPhone,
                             String sourcePlatform, String exportDate, String events,
                             String lastPurchaseDate, String marketingStatus, String proofRef) {

        /** A row without provenance columns. */
        public RawContact(int rowNumber, String rawEmail, String name, String rawPhone) {
            this(rowNumber, rawEmail, name, rawPhone, null, null, null, null, null, null);
        }
    }

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
        int platformIdx = -1, exportDateIdx = -1, eventsIdx = -1, lastPurchaseIdx = -1,
                statusIdx = -1, proofIdx = -1;
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim().toLowerCase(java.util.Locale.ROOT);
            if (isForbidden(h)) {
                String column = header.get(i).trim();
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.IMPORT_FORBIDDEN_COLUMN,
                        "Column '" + column + "' is not allowed: files must not contain ID numbers, "
                                + "payment data, IP addresses or health data",
                        java.util.Map.of("column", column));
            }
            if (emailIdx < 0 && EMAIL_HEADERS.contains(h)) emailIdx = i;
            else if (platformIdx < 0 && SOURCE_PLATFORM.equals(h)) platformIdx = i;
            else if (exportDateIdx < 0 && EXPORT_DATE.equals(h)) exportDateIdx = i;
            else if (eventsIdx < 0 && EVENTS.equals(h)) eventsIdx = i;
            else if (lastPurchaseIdx < 0 && LAST_PURCHASE_DATE.equals(h)) lastPurchaseIdx = i;
            else if (statusIdx < 0 && MARKETING_STATUS.equals(h)) statusIdx = i;
            else if (proofIdx < 0 && PROOF_REF.equals(h)) proofIdx = i;
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
                    phone.isBlank() ? null : phone,
                    orNull(cell(row, platformIdx)), orNull(cell(row, exportDateIdx)),
                    orNull(cell(row, eventsIdx)), orNull(cell(row, lastPurchaseIdx)),
                    orNull(cell(row, statusIdx)), orNull(cell(row, proofIdx))));
        }
        return out;
    }

    /**
     * True when a (lower-cased) header names data that must never be imported. Headers are
     * split into words, so {@code customer_iban} and {@code IP Address} both match while
     * {@code order id} and {@code shipping} do not.
     */
    static boolean isForbidden(String lowerHeader) {
        String words = lowerHeader.replaceAll("[^a-z0-9]+", " ").trim();
        if (words.isEmpty()) return false;
        for (String w : words.split(" ")) {
            if (FORBIDDEN_WORDS.contains(w)) return true;
        }
        String padded = " " + words + " ";
        for (String phrase : FORBIDDEN_PHRASES) {
            if (padded.contains(" " + phrase + " ")) return true;
        }
        return false;
    }

    private static String orNull(String v) {
        return v.isBlank() ? null : v;
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
