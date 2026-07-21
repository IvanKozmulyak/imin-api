package com.imin.iminapi.email;

/**
 * Locale helpers for organizer-facing transactional emails.
 *
 * <p>Supported languages are {@code en}/{@code es}/{@code fr}/{@code uk}. Any
 * null, blank, or unrecognized value normalizes to {@code en} — English is the
 * bulletproof fallback for both template selection and subject lines.
 */
public final class EmailLocale {

    private EmailLocale() {}

    /**
     * Normalizes a raw locale to one of {@code es}/{@code fr}/{@code uk}, or
     * {@code en} for null/blank/unsupported input. Trims and lowercases first.
     */
    public static String normalize(String locale) {
        if (locale == null) {
            return "en";
        }
        String l = locale.trim().toLowerCase();
        return switch (l) {
            case "es", "fr", "uk" -> l;
            default -> "en";
        };
    }

    /**
     * Picks the localized string for the normalized locale, falling back to the
     * English value for {@code en}/unsupported input. Used for subject lines.
     */
    public static String choose(String locale, String en, String es, String fr, String uk) {
        return switch (normalize(locale)) {
            case "es" -> es;
            case "fr" -> fr;
            case "uk" -> uk;
            default -> en;
        };
    }
}
