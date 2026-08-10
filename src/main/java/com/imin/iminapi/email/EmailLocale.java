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
     * Storage-oriented variant of {@link #normalize(String)}: returns {@code null}
     * for null/blank/unsupported input instead of collapsing it to {@code en}.
     *
     * <p>Use this when persisting a buyer- or user-supplied locale, so the column
     * distinguishes "explicitly English" from "no preference expressed". Rendering
     * still goes through {@link #normalize(String)}, which treats both as English.
     */
    public static String normalizeOrNull(String locale) {
        if (locale == null) {
            return null;
        }
        String l = locale.trim().toLowerCase(java.util.Locale.ROOT);
        // (Locale.ROOT keeps the mapping stable under a Turkish default locale.)
        return switch (l) {
            case "en", "es", "fr", "uk" -> l;
            default -> null;
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
