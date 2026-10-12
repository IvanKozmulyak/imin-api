package com.imin.iminapi.service.poster;

import com.imin.iminapi.service.poster.PosterTextValidationClient.Seen;
import com.imin.iminapi.service.poster.PosterTextValidationClient.Transcript;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PosterTextMatcherTest {

    private static final String TITLE = "The Midnight Bean Society";
    private static final String DATE = "31 OCT 2026";
    private static final List<String> REQUIRED = List.of(TITLE, DATE);
    private static final List<String> ALLOWED = List.of(TITLE, DATE, "PARIS", "KOLA", "Jean-Luc");

    private static Seen s(String text) {
        return new Seen(text, 1);
    }

    private static Seen s(String text, int count) {
        return new Seen(text, count);
    }

    // Catches: a misspelled, re-cased or repeated title shipping because the gate called it "near enough"
    // (prod 2026-10-12: "BEAn SOCIETY" with ghost repeats passed), and a clean re-cased title rejected.
    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void match_appliesCaseRepeatAndJoinRules(String name, List<Seen> texts, List<String> unreadable,
                                             List<String> missing, List<String> extra) {
        PosterTextMatcher.Match m = PosterTextMatcher.match(new Transcript(texts, unreadable), REQUIRED, ALLOWED);

        assertThat(m.missing()).containsExactlyElementsOf(missing);
        assertThat(m.extra()).containsExactlyElementsOf(extra);
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                Arguments.of("exact", List.of(s(TITLE), s(DATE)), List.of(), List.of(), List.of()),
                Arguments.of("all caps", List.of(s("THE MIDNIGHT BEAN SOCIETY"), s(DATE)), List.of(), List.of(), List.of()),
                Arguments.of("one letter re-cased", List.of(s("THE MIDNIGHT BEAn SOCIETY"), s(DATE)), List.of(),
                        List.of(TITLE), List.of("THE MIDNIGHT BEAn SOCIETY")),
                Arguments.of("lowercase", List.of(s("the midnight bean society"), s(DATE)), List.of(), List.of(), List.of()),
                Arguments.of("Title Case of a string typed in caps", List.of(s(TITLE), s(DATE), s("Paris")), List.of(),
                        List.of(), List.of()),
                Arguments.of("each word in its own allowed case", List.of(s("THE Midnight bean SOCIETY"), s(DATE)),
                        List.of(), List.of(), List.of()),
                Arguments.of("one letter re-cased inside a word", List.of(s("THE MIDNIGHT BeaN SOCIETY"), s(DATE)),
                        List.of(), List.of(TITLE), List.of("THE MIDNIGHT BeaN SOCIETY")),
                Arguments.of("two required strings in one block", List.of(s("THE MIDNIGHT BEAN SOCIETY 31 OCT 2026")),
                        List.of(), List.of(), List.of()),
                Arguments.of("two required strings in one block, reversed", List.of(s("31 OCT 2026 The Midnight Bean Society")),
                        List.of(), List.of(), List.of()),
                Arguments.of("title split over two blocks", List.of(s("THE MIDNIGHT"), s("BEAN SOCIETY"), s(DATE)),
                        List.of(), List.of(), List.of()),
                Arguments.of("title three times", List.of(s("THE MIDNIGHT BEAN SOCIETY", 3), s(DATE)), List.of(),
                        List.of(), List.of("THE MIDNIGHT BEAN SOCIETY ×2")),
                Arguments.of("ghost repeats", List.of(s("THE MIDNIGHT"), s("BEAN SOCIETY", 5), s(DATE)), List.of(),
                        List.of(), List.of("BEAN SOCIETY ×4")),
                Arguments.of("date fragment", List.of(s(TITLE), s(DATE), s("31 OCT")), List.of(),
                        List.of(), List.of("31 OCT")),
                Arguments.of("city once, DJ twice", List.of(s(TITLE), s(DATE), s("PARIS"), s("KOLA", 2)), List.of(),
                        List.of(), List.of("KOLA")),
                Arguments.of("unreadable marks", List.of(s(TITLE), s(DATE)), List.of("fine print, bottom-left"),
                        List.of(), List.of("[fine print, bottom-left]")),
                Arguments.of("line break, double space, en dash",
                        List.of(s("THE MIDNIGHT\nBEAN  SOCIETY"), s(DATE), s("JEAN–LUC")), List.of(),
                        List.of(), List.of()),
                Arguments.of("nothing read", List.of(), List.of(), List.of(TITLE, DATE), List.of()),
                Arguments.of("invented words", List.of(s(TITLE), s(DATE), s("FREE ENTRY")), List.of(),
                        List.of(), List.of("FREE ENTRY")));
    }

    // Catches: fine print in the margins passing because it was never compared, or the title cut by a
    // strip edge failing an otherwise clean poster.
    @ParameterizedTest(name = "{0}")
    @MethodSource("edgeRows")
    void edgeExtras_flagOnlyTextThatIsNotTheEventText(String name, List<Transcript> bands, List<String> expected) {
        assertThat(PosterTextMatcher.edgeExtras(bands, REQUIRED, ALLOWED)).containsExactlyElementsOf(expected);
    }

    private static Transcript t(List<Seen> texts, List<String> unreadable) {
        return new Transcript(texts, unreadable);
    }

    private static final Transcript EMPTY = new Transcript(List.of(), List.of());

    static Stream<Arguments> edgeRows() {
        return Stream.of(
                Arguments.of("cut-off title fragment", List.of(t(List.of(s("SOCIE")), List.of()), EMPTY, EMPTY, EMPTY),
                        List.of()),
                Arguments.of("credit line", List.of(EMPTY, t(List.of(s("© ODD MOOD CO.")), List.of()), EMPTY, EMPTY),
                        List.of("[edge bottom: © ODD MOOD CO.]")),
                Arguments.of("unreadable column", List.of(EMPTY, EMPTY, t(List.of(), List.of("vertical marks")), EMPTY),
                        List.of("[edge left: vertical marks]")),
                Arguments.of("single letter", List.of(EMPTY, EMPTY, EMPTY, t(List.of(s("Q")), List.of())),
                        List.of()),
                Arguments.of("nothing", List.of(EMPTY, EMPTY, EMPTY, EMPTY), List.of()));
    }
}
