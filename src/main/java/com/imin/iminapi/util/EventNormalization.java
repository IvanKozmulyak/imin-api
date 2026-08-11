package com.imin.iminapi.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Write-time normalisation for the three event fields the buyer's browse facets group on:
 * venue city, venue country and genre (V82).
 *
 * <p>Live data drifted because nothing normalised on the way in: one city arrived as
 * {@code Metz/FR}, {@code Metz/null} and {@code METZ/''} — three chips for one place — and
 * one genre as both {@code techno} and {@code Techno}, which {@code ?genre=} (an exact,
 * case-sensitive match) treats as two different queries. The cure is here, at the write
 * path, plus the V82 backfill for rows written before it.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>city</b> — whitespace runs collapsed to one space, then trimmed. Case is
 *       <b>preserved</b>: title-casing destroys real names ({@code 's-Hertogenbosch},
 *       {@code L'Aquila}), and upper/lower-casing them is worse. Merging the case variants
 *       is the job of {@link #cityKey(String)}, not of the display string.</li>
 *   <li><b>country</b> — trimmed and upper-cased, or {@code null}. Never the empty string:
 *       {@code ''} and {@code NULL} looked like two different countries to a {@code GROUP BY},
 *       which is exactly what split Metz in three.</li>
 *   <li><b>genre</b> — whitespace runs collapsed to one space, then trimmed. Case is
 *       <b>preserved</b>, exactly like the city: both frontends render the stored string
 *       verbatim, and {@code imin-webapp}'s wizard picks it out of a closed Title-Case
 *       {@code GENRES} list, so a case-folded value would render as an empty "Music style"
 *       field on reopen. Merging the case variants is the job of {@link #genreKey(String)}.</li>
 * </ul>
 *
 * <p>The SQL in {@code V82__normalize_event_facets.sql} mirrors these rules exactly
 * ({@code trim(regexp_replace(x, '\s+', ' ', 'g'))}); Java's {@code \s} and PostgreSQL's
 * {@code \s} cover the same six characters, and H2 (which backs the test suite) runs the
 * same Java regex engine. Keep the two in step — the derived key is computed by this class
 * on every write and by that migration for every pre-existing row.
 */
public final class EventNormalization {

    private EventNormalization() {}

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern ISO_ALPHA2 = Pattern.compile("[A-Z]{2}");

    /**
     * Display spelling of a city: internal whitespace collapsed, ends trimmed, case as typed.
     * Returns {@code ""} for null/blank — {@code events.venue_city} is {@code NOT NULL DEFAULT ''}.
     */
    public static String city(String raw) {
        return collapse(raw);
    }

    /**
     * Merge key for a city — {@link #city(String)} lower-cased. This is what the city facet
     * groups by and what {@code ?city=} matches, so a chip's count and its own result page can
     * never disagree. Returns {@code ""} for null/blank (the "no city" rows, which the facet drops).
     */
    public static String cityKey(String raw) {
        return collapse(raw).toLowerCase(Locale.ROOT);
    }

    /**
     * ISO-3166 alpha-2 country, upper-cased, or {@code null} when absent. Blank in ⇒ {@code null}
     * out — never {@code ""}. Does not validate; callers that accept organizer input pair this
     * with {@link #isCountryCode(String)} and reject what fails.
     */
    public static String country(String raw) {
        if (raw == null) return null;
        String trimmed = collapse(raw);
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /** True when {@code normalized} (already through {@link #country}) is two A–Z letters. */
    public static boolean isCountryCode(String normalized) {
        return normalized != null && ISO_ALPHA2.matcher(normalized).matches();
    }

    /**
     * Display spelling of a genre: internal whitespace collapsed, ends trimmed, case as typed.
     * {@code null} in ⇒ {@code null} out so a caller can keep "field absent" distinct from
     * "field cleared"; blank in ⇒ {@code ""}, matching the {@code NOT NULL DEFAULT ''} column.
     *
     * <p>Case is deliberately NOT folded. Both frontends print this string as-is
     * ({@code imin-public}'s card/detail chips, {@code imin-webapp}'s event hero), and the
     * webapp's create/edit wizard binds it to a closed Title-Case {@code GENRES} option list —
     * a lower-cased value matches no option and the "Music style" field renders empty.
     */
    public static String genre(String raw) {
        if (raw == null) return null;
        return collapse(raw);
    }

    /**
     * Merge key for a genre — {@link #genre(String)} lower-cased. This is what the genre facet
     * groups by and what {@code ?genre=} matches, so {@code Techno}, {@code techno} and
     * {@code  TECHNO } are one chip whose result page is exactly what its label promises.
     * Returns {@code ""} for null/blank (the "no genre" rows, which the facet drops).
     */
    public static String genreKey(String raw) {
        return collapse(raw).toLowerCase(Locale.ROOT);
    }

    /**
     * Collapse-then-trim, in that order, so the result matches the migration's
     * {@code trim(regexp_replace(x, '\s+', ' ', 'g'))} character for character.
     */
    private static String collapse(String raw) {
        if (raw == null) return "";
        return WHITESPACE_RUN.matcher(raw).replaceAll(" ").trim();
    }
}
