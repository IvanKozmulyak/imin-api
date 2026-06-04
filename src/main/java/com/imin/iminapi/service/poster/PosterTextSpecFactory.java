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
        List<String> required = new ArrayList<>();
        if (has(r.title())) required.add(r.title().trim());
        if (r.date() != null) required.add(r.date().format(DATE).toUpperCase(Locale.ENGLISH));
        if (has(r.location())) required.add(r.location().trim());

        List<String> allowed = new ArrayList<>(required);
        if (has(r.city())) allowed.add(r.city().trim().toUpperCase(Locale.ENGLISH));
        if (has(r.djName())) {
            Arrays.stream(r.djName().split("[,·/&]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(allowed::add);
        }

        String prompt = """
                EXACT TEXT CONTRACT:
                Render the following required text exactly as written, as the visible poster typography:
                %s

                You may also use these optional text elements if they fit the composition:
                %s

                No other words, filler text, lorem ipsum, fake letters, pseudo-text, logos, watermarks, or invented copy.
                If a text element is too long, reduce font size or change layout. Do not misspell it.
                """.formatted(quoteLines(required), quoteLines(allowed));

        return new PosterTextSpec(List.copyOf(required), List.copyOf(allowed), prompt);
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static String quoteLines(List<String> values) {
        if (values.isEmpty()) return "- none";
        return values.stream().map(v -> "- \"" + v + "\"").reduce((a, b) -> a + "\n" + b).orElse("- none");
    }
}
