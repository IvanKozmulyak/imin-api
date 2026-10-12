package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PosterTextSpecFactoryTest {
    PosterTextSpecFactory factory = new PosterTextSpecFactory();

    private static EventCreatorRequest request(String title, LocalDate date, String venue) {
        return new EventCreatorRequest(
                "warehouse rave", "energetic", "techno", "Berlin", date, List.of("instagram"),
                "DJ A, DJ B", venue, title, null,
                "Schnellerstrasse 137", "https://imin.wtf/e/big-night", "berlin_minimal");
    }

    @Test
    void fromRequest_buildsRequiredAndAllowedText() {
        var spec = factory.from(request("BIG NIGHT - BERLIN", LocalDate.of(2026, 6, 7), "RSO"));

        assertThat(spec.required()).containsExactly("BIG NIGHT - BERLIN", "7 JUN 2026", "RSO");
        assertThat(spec.allowed()).contains("DJ A", "DJ B", "BERLIN");
        // Date and city are built upper-case, so the gate takes them only as written or in ALL CAPS.
        assertThat(spec.exact()).containsExactly("7 JUN 2026", "BERLIN");
    }

    // Catches: the date left out of posters (the renderer drops text it reads late) and DJ or city
    // names the organizer never asked to print. Exact sentences: order, quoting and nothing else.
    @ParameterizedTest(name = "{0}")
    @MethodSource("sentences")
    void textSentence_quotesPresentElementsFirstInOrder(String name, String title, LocalDate date, String venue,
                                                        String expected) {
        assertThat(factory.from(request(title, date, venue)).textSentence()).isEqualTo(expected);
    }

    static Stream<Arguments> sentences() {
        LocalDate june7 = LocalDate.of(2026, 6, 7);
        return Stream.of(
                Arguments.of("title, date and venue", "BIG NIGHT - BERLIN", june7, "RSO",
                        "A finished event poster whose only text is \"BIG NIGHT - BERLIN\" as the huge headline, "
                                + "the date \"7 JUN 2026\" in large bold type directly under the title, "
                                + "and the venue \"RSO\" in smaller type below the date."),
                Arguments.of("date and venue", null, june7, "RSO",
                        "A finished event poster whose only text is the date \"7 JUN 2026\" as the huge headline "
                                + "and the venue \"RSO\" in smaller type below the date."),
                Arguments.of("title and date", "BIG NIGHT - BERLIN", june7, " ",
                        "A finished event poster whose only text is \"BIG NIGHT - BERLIN\" as the huge headline "
                                + "and the date \"7 JUN 2026\" in large bold type directly under the title."),
                Arguments.of("nothing", null, null, null, "A finished event poster with no text at all."));
    }
}
