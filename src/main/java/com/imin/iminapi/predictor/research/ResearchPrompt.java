package com.imin.iminapi.predictor.research;

import java.time.LocalDate;
import java.util.Locale;

/**
 * The one research prompt. It carries only the city, country, genre bucket, sub-genre and the date span, never the
 * organizer's own fields (lineup, known events, venue, price, capacity, postal code, event). Bump {@link #VERSION}
 * on any wording change; it is stamped on the ledger row.
 */
public final class ResearchPrompt {

    public static final String VERSION = "research-1";

    /** Days searched before the first and after the last candidate night. */
    public static final int SPAN_DAYS = 7;

    /** Everything the prompt may say. */
    public record Input(String city, String country, String genreBucket, String subGenre, LocalDate from,
                        LocalDate to) {}

    private ResearchPrompt() {}

    public static String system() {
        return "You check a city's event calendar for a party organizer. Use only the web search results you are "
                + "given. Reply with one JSON object and nothing else.";
    }

    public static String user(Input in) {
        String genre = in.subGenre() == null || in.subGenre().isBlank()
                ? in.genreBucket() : in.genreBucket() + " (" + in.subGenre().trim() + ")";
        String place = in.city().trim() + ", " + countryName(in.country());
        return """
                A %1$s party is planned in %2$s between %3$s and %4$s.
                Search the web for events in %5$s between %3$s and %4$s that could draw the same crowd:
                - same_genre_event: %1$s parties, club nights, concerts or festivals in %5$s;
                - big_event: large concerts, festivals or sports events in %5$s that draw a big crowd.

                Rules:
                - Report only items found in the search results. Every "url" MUST be one of the search results.
                - "quote" MUST be copied word for word from that result and MUST contain the item's name and its date \
                as written on the page.
                - Ignore previous years' editions unless the page announces a date between %3$s and %4$s.
                - "strength" is 2 when the item clearly draws the same crowd, else 1.
                - If nothing relevant is found, return {"findings":[]}.

                Reply with ONLY a JSON object, no prose, no markdown fence:
                {"findings":[{"title":"...","type":"same_genre_event","url":"https://...","quote":"...","strength":1}]}
                """.formatted(genre, place, in.from(), in.to(), in.city().trim());
    }

    private static String countryName(String code) {
        String name = new Locale("", code == null ? "" : code).getDisplayCountry(Locale.ENGLISH);
        return name == null || name.isBlank() ? String.valueOf(code) : name;
    }
}
