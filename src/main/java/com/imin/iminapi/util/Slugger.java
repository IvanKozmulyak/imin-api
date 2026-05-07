package com.imin.iminapi.util;

import java.text.Normalizer;

/**
 * Utility for converting arbitrary strings into URL-safe slugs.
 */
public final class Slugger {

    private Slugger() {}

    /**
     * Converts {@code input} to a URL-safe slug.
     * <ol>
     *   <li>NFD-normalizes and strips combining marks (diacritics)</li>
     *   <li>Lowercases</li>
     *   <li>Replaces runs of non-{@code [a-z0-9]} characters with {@code -}</li>
     *   <li>Trims leading/trailing hyphens</li>
     *   <li>Returns {@code "org"} if the result would be empty</li>
     *   <li>Truncates to 200 characters and re-trims hyphens</li>
     * </ol>
     */
    public static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "org";
        }

        // NFD normalize then strip combining marks (diacritics)
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        String stripped = normalized.replaceAll("\\p{M}", "");

        // Lowercase
        String lower = stripped.toLowerCase();

        // Replace runs of non-[a-z0-9] with a single hyphen
        String hyphenated = lower.replaceAll("[^a-z0-9]+", "-");

        // Trim leading/trailing hyphens
        String trimmed = hyphenated.replaceAll("^-+|-+$", "");

        if (trimmed.isEmpty()) {
            return "org";
        }

        // Truncate to 200 characters
        if (trimmed.length() > 200) {
            trimmed = trimmed.substring(0, 200);
            // Re-trim trailing hyphens after truncation
            trimmed = trimmed.replaceAll("-+$", "");
            // Edge case: if truncation left us empty, fall back
            if (trimmed.isEmpty()) {
                return "org";
            }
        }

        return trimmed;
    }
}
