package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.PosterTextSpec;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class PosterTextSpecFactory {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public PosterTextSpec from(EventCreatorRequest r) {
        String title = has(r.title()) ? r.title().trim() : null;
        String date = r.date() == null ? null : r.date().format(DATE).toUpperCase(Locale.ENGLISH);
        String venue = has(r.location()) ? r.location().trim() : null;

        List<String> required = new ArrayList<>();
        if (title != null) required.add(title);
        if (date != null) required.add(date);
        if (venue != null) required.add(venue);

        // DJ names and the city stay allowed for the gate but are never asked for: in DJ mode the
        // character reference carries the DJ, and extra strings were where invented text crept in.
        List<String> allowed = new ArrayList<>(required);
        List<String> exact = new ArrayList<>();
        if (date != null) exact.add(date);
        if (has(r.city())) {
            allowed.add(r.city().trim().toUpperCase(Locale.ENGLISH));
            exact.add(r.city().trim().toUpperCase(Locale.ENGLISH));
        }
        if (has(r.djName())) {
            Arrays.stream(r.djName().split("[,·/&]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(allowed::add);
        }

        return new PosterTextSpec(List.copyOf(required), List.copyOf(allowed), textSentence(title, date, venue),
                List.copyOf(exact));
    }

    /**
     * The render prompt's first sentence: the renderer drops text it reads late, so every present
     * element is quoted up front, top to bottom (title, date, venue), and nothing else is named.
     */
    static String textSentence(String title, String date, String venue) {
        List<String> parts = new ArrayList<>();
        String above = null;
        if (title != null) {
            parts.add("\"" + title + "\" as the huge headline");
            above = "title";
        }
        if (date != null) {
            parts.add(above == null
                    ? "the date \"" + date + "\" as the huge headline"
                    : "the date \"" + date + "\" in large bold type directly under the " + above);
            above = "date";
        }
        if (venue != null) {
            parts.add(above == null
                    ? "the venue \"" + venue + "\" as the huge headline"
                    : "the venue \"" + venue + "\" in smaller type below the " + above);
        }
        if (parts.isEmpty()) return "A finished event poster with no text at all.";
        return "A finished event poster whose only text is " + joinWithAnd(parts) + ".";
    }

    private static String joinWithAnd(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        if (parts.size() == 2) return parts.get(0) + " and " + parts.get(1);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + ", and " + parts.get(parts.size() - 1);
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }
}
