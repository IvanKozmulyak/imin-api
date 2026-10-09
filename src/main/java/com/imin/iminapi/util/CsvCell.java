package com.imin.iminapi.util;

/** One CSV field for files organizers open in Excel or Sheets. */
public final class CsvCell {

    private CsvCell() {}

    /**
     * RFC-4180 escaping (wrap in quotes and double any embedded quote when needed), plus a
     * CSV-injection guard: a value starting with {@code = + - @}, tab or CR is prefixed with a
     * single quote so spreadsheets treat it as text. Buyer emails are buyer-controlled and
     * {@code =cmd|'/C calc'!A0@example.com} passes checkout's "contains @" check.
     */
    public static String escape(String v) {
        if (v == null) return "";
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
