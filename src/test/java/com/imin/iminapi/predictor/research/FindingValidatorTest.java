package com.imin.iminapi.predictor.research;

import com.imin.iminapi.predictor.research.FindingValidator.Checked;
import com.imin.iminapi.predictor.research.FindingValidator.Cited;
import com.imin.iminapi.predictor.research.FindingValidator.Drop;
import com.imin.iminapi.predictor.research.FindingValidator.Reported;
import com.imin.iminapi.predictor.rules.Finding;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.rules.QuestionBank.Window;
import com.imin.iminapi.predictor.rules.QuestionBankLoader;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** One rule per test: a reported item that passes every other rule and breaks only the one named. */
class FindingValidatorTest {

    private static final QuestionBank BANK = QuestionBankLoader.load(new DefaultResourceLoader());
    private static final LocalDate SAT_17_OCT = LocalDate.of(2026, 10, 17);
    private static final Instant FETCHED = Instant.parse("2026-10-01T10:00:00Z");
    private static final String URL = "https://www.infoconcert.com/concert/amelie-lens-rex-club.html";
    private static final String QUOTE = "Amelie Lens au Rex Club le samedi 17 octobre 2026";
    private static final String TITLE = "Amelie Lens - Rex Club";

    private static Cited page(String url, String title, String content) {
        return new Cited(url, title, content);
    }

    private static Cited page(String quoteInside) {
        return page(URL, "Concerts techno à Paris", "Agenda. " + quoteInside + ", 23h. Billets 25 EUR.");
    }

    private static Reported item(String quote, String type, Integer strength) {
        return new Reported(TITLE, URL + "?utm_source=x", quote, type, strength);
    }

    private static Reported item(String quote) {
        return item(quote, "same_genre_event", 2);
    }

    private static FindingValidator.Result check(Reported r, Cited c) {
        return FindingValidator.check(List.of(r), List.of(c), "Paris");
    }

    private static List<Finding> findings(Reported r, Cited c, LocalDate candidate) {
        FindingValidator.Result res = check(r, c);
        return FindingValidator.assign(res.kept(), candidate, "Paris", BANK, FETCHED);
    }

    @Test
    void sameGenreWithinANightIsQuestion21() {
        List<Finding> out = findings(item(QUOTE), page(QUOTE), SAT_17_OCT);

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.questionId()).isEqualTo("2.1");
            assertThat(f.sourceKind()).isEqualTo(SourceKind.WEB);
            assertThat(f.kind()).isEqualTo(Kind.RISK);
            assertThat(f.status()).isEqualTo(Finding.Status.FOUND);
            assertThat(f.window()).isEqualTo(Window.NIGHT);
            assertThat(f.weight()).isEqualTo(3);
            assertThat(f.strength()).isEqualTo(2);
            // The stored link is the search result the quote was checked against.
            assertThat(f.url()).isEqualTo(URL);
            assertThat(f.quote()).isEqualTo(QUOTE);
            assertThat(f.fetchedAt()).isEqualTo(FETCHED);
            // The name is the longest title run the quote holds, as the quote writes it.
            assertThat(f.facts()).isEqualTo(Map.of("name", "Amelie Lens", "date", "2026-10-17"));
        });
        // The night before is still within a night of the candidate.
        assertThat(findings(item(QUOTE), page(QUOTE), LocalDate.of(2026, 10, 18)))
                .extracting(Finding::questionId).containsExactly("2.1");
    }

    @Test
    void sameGenreWithinTheWeekIsQuestion22() {
        String quote = "Amelie Lens au Rex Club le mercredi 21 octobre 2026";
        assertThat(findings(item(quote), page(quote), SAT_17_OCT)).singleElement().satisfies(f -> {
            assertThat(f.questionId()).isEqualTo("2.2");
            assertThat(f.window()).isEqualTo(Window.WEEK);
            assertThat(f.weight()).isEqualTo(2);
            assertThat(f.facts()).containsEntry("date", "2026-10-21");
        });
    }

    @Test
    void urlNotInResultsDropped() {
        Cited other = page("https://www.infoconcert.com/other-page.html", "Concerts techno à Paris", "x " + QUOTE);
        FindingValidator.Result res = check(item(QUOTE), other);
        assertThat(res.kept()).isEmpty();
        assertThat(res.dropped()).containsEntry(Drop.URL_NOT_CITED, 1);
    }

    @Test
    void quoteNotVerbatimInTheExcerptDropped() {
        FindingValidator.Result res = check(item(QUOTE), page("Amelie Lens au Rex Club le 17 octobre"));
        assertThat(res.kept()).isEmpty();
        assertThat(res.dropped()).containsEntry(Drop.QUOTE_NOT_IN_EXCERPT, 1);
    }

    @Test
    void blocklistedDomainDropped() {
        String ra = "https://ra.co/events/123";
        Reported r = new Reported(TITLE, ra, QUOTE, "same_genre_event", 2);
        FindingValidator.Result res = check(r, page(ra, "Amelie Lens at Rex Club, Paris", "x " + QUOTE));
        assertThat(res.kept()).isEmpty();
        assertThat(res.dropped()).containsEntry(Drop.BLOCKLISTED, 1);
    }

    @Test
    void wrongCityDropped() {
        String quote = "La Mano 1.9 à l'Accor Arena le samedi 17 octobre 2026";
        String url = "https://www.infoconcert.com/la-mano.html";
        Reported r = new Reported("La Mano 1.9 - Accor Arena", url, quote, "big_event", 2);
        FindingValidator.Result res = FindingValidator.check(List.of(r),
                List.of(page(url, "La Mano 1.9 en concert", "Agenda. " + quote)), "Marseille");
        assertThat(res.kept()).isEmpty();
        assertThat(res.dropped()).containsEntry(Drop.WRONG_CITY, 1);
        // The same item is kept for the city the page names.
        assertThat(FindingValidator.check(List.of(r), List.of(page(url, "La Mano 1.9 à Paris", "Agenda. " + quote)),
                "Paris").kept()).hasSize(1);
    }

    @Test
    void quoteWithoutAWordOfTheTitleDropped() {
        String quote = "Saturday 17 October 2026";
        Reported r = new Reported("BLACK LEGENDS", URL, quote, "same_genre_event", 2);
        FindingValidator.Result res = check(r, page(quote));
        assertThat(res.kept()).isEmpty();
        assertThat(res.dropped()).containsEntry(Drop.NO_TITLE_IN_QUOTE, 1);
    }

    @Test
    void nameIsTheLongestTitleRunAsTheQuoteWritesIt() {
        String quote = "Agenda : AMÉLIE LENS b2b Charlotte de Witte au Rex Club, samedi 17 octobre 2026";
        Reported r = new Reported("Amelie Lens b2b Charlotte de Witte | Rex Club Paris (Lineup)", URL, quote,
                "same_genre_event", 2);

        FindingValidator.Result res = check(r, page(quote));

        assertThat(res.kept()).singleElement().extracting(Checked::name)
                .isEqualTo("AMÉLIE LENS b2b Charlotte de Witte");
        assertThat(FindingValidator.assign(res.kept(), SAT_17_OCT, "Paris", BANK, FETCHED)).singleElement()
                .satisfies(f -> assertThat(f.facts()).containsEntry("name", "AMÉLIE LENS b2b Charlotte de Witte"));
    }

    @Test
    void titleSharingOnlyOneWordWithTheQuoteDropped() {
        // "Lens" and "2026" are both quoted, but never next to each other as the title has them.
        Reported r = new Reported("Lens Festival 2026", URL, QUOTE, "same_genre_event", 2);
        FindingValidator.Result res = check(r, page(QUOTE));
        assertThat(res.kept()).isEmpty();
        assertThat(res.dropped()).containsEntry(Drop.NO_TITLE_IN_QUOTE, 1);
    }

    @Test
    void runOfDateWordsIsNoName() {
        // "17 octobre" is quoted as the title has it, but a date names no event.
        Reported r = new Reported("Paris le 17 octobre", URL, QUOTE, "same_genre_event", 2);
        FindingValidator.Result res = check(r, page(QUOTE));
        assertThat(res.kept()).isEmpty();
        assertThat(res.dropped()).containsEntry(Drop.NO_TITLE_IN_QUOTE, 1);
    }

    @Test
    void oneWordTitleNeedsFourLetters() {
        FindingValidator.Result kept = check(new Reported("Amelie", URL, QUOTE, "same_genre_event", 2), page(QUOTE));
        assertThat(kept.kept()).singleElement().extracting(Checked::name).isEqualTo("Amelie");

        FindingValidator.Result shortWord = check(new Reported("Rex", URL, QUOTE, "same_genre_event", 2),
                page(QUOTE));
        assertThat(shortWord.kept()).isEmpty();
        assertThat(shortWord.dropped()).containsEntry(Drop.NO_TITLE_IN_QUOTE, 1);
    }

    @Test
    void oneWebFindingPerQuestionPerNightNearestThenStrongest() {
        String friday = "Amelie Lens au Rex Club le vendredi 16 octobre 2026";
        String other = "https://www.sortiraparis.com/amelie-lens";
        List<Finding> out = FindingValidator.assign(List.of(
                new Checked("Amelie Lens", URL + "/b", friday, FindingValidator.Type.SAME_GENRE_EVENT, 2),
                new Checked("Amelie Lens", URL, QUOTE, FindingValidator.Type.SAME_GENRE_EVENT, 1),
                new Checked("Amelie Lens", other, QUOTE, FindingValidator.Type.SAME_GENRE_EVENT, 2)),
                SAT_17_OCT, "Paris", BANK, FETCHED);

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.questionId()).isEqualTo("2.1");
            assertThat(f.url()).isEqualTo(other);
            assertThat(f.strength()).isEqualTo(2);
            assertThat(f.facts()).containsEntry("date", "2026-10-17");
        });
    }

    @Test
    void oneQuoteReportedAsBothTypesKeptOnceAsSameGenre() {
        for (List<String> order : List.of(List.of("big_event", "same_genre_event"),
                List.of("same_genre_event", "big_event"))) {
            FindingValidator.Result res = FindingValidator.check(List.of(item(QUOTE, order.get(0), 2),
                    item(QUOTE, order.get(1), 2)), List.of(page(QUOTE)), "Paris");

            assertThat(res.kept()).as(order.toString()).singleElement()
                    .extracting(Checked::type).isEqualTo(FindingValidator.Type.SAME_GENRE_EVENT);
        }
    }

    @Test
    void bigEventOnAPageThatGaveTheNightsSameGenreFindingSkipped() {
        String bigQuote = "Rex Club: Amelie Lens, samedi 17 octobre 2026, complet";
        for (boolean bigFirst : List.of(true, false)) {
            Checked same = new Checked("Amelie Lens", URL, QUOTE, FindingValidator.Type.SAME_GENRE_EVENT, 2);
            Checked big = new Checked("Amelie Lens", URL + "/?utm_source=x", bigQuote,
                    FindingValidator.Type.BIG_EVENT, 2);
            List<Finding> out = FindingValidator.assign(bigFirst ? List.of(big, same) : List.of(same, big),
                    SAT_17_OCT, "Paris", BANK, FETCHED);

            assertThat(out).as("bigFirst=" + bigFirst).extracting(Finding::questionId).containsExactly("2.1");
        }
    }

    private static List<String> questions(Checked a, Checked b, boolean bFirst) {
        return FindingValidator.assign(bFirst ? List.of(b, a) : List.of(a, b), SAT_17_OCT, "Paris", BANK, FETCHED)
                .stream().map(Finding::questionId).toList();
    }

    @Test
    void agendaPageKeepsADifferentBigEventOnTheSameNight() {
        Checked same = new Checked("Amelie Lens", URL, QUOTE, FindingValidator.Type.SAME_GENRE_EVENT, 2);
        Checked big = new Checked("Fête des Lumières", URL,
                "Fête des Lumières sur les quais le samedi 17 octobre 2026", FindingValidator.Type.BIG_EVENT, 2);
        for (boolean bigFirst : List.of(true, false)) {
            assertThat(questions(same, big, bigFirst)).as("bigFirst=" + bigFirst)
                    .containsExactlyInAnyOrder("2.1", "5.3");
        }
    }

    @Test
    void sameEventOnTwoSitesCountsOnce() {
        // A club page gives the night's same-genre event; a city agenda lists the same night as a big event.
        Checked same = new Checked("Amelie Lens", URL, QUOTE, FindingValidator.Type.SAME_GENRE_EVENT, 2);
        Checked big = new Checked("Amelie Lens", URL + "/agenda",
                "Amelie Lens, samedi 17 octobre 2026 : la soirée techno du week-end", FindingValidator.Type.BIG_EVENT, 2);
        for (boolean bigFirst : List.of(true, false)) {
            assertThat(questions(same, big, bigFirst)).as("bigFirst=" + bigFirst).containsExactly("2.1");
        }
    }

    @Test
    void differentBigEventOnAnotherSiteIsKept() {
        Checked same = new Checked("Amelie Lens", URL, QUOTE, FindingValidator.Type.SAME_GENRE_EVENT, 2);
        Checked big = new Checked("Fête des Lumières", URL + "/agenda",
                "Fête des Lumières sur les quais le samedi 17 octobre 2026", FindingValidator.Type.BIG_EVENT, 2);
        for (boolean bigFirst : List.of(true, false)) {
            assertThat(questions(same, big, bigFirst)).as("bigFirst=" + bigFirst)
                    .containsExactlyInAnyOrder("2.1", "5.3");
        }
    }

    @Test
    void oneEventQuotedTwiceAsBothTypesCountsOnce() {
        // The names differ; the big item's name is in the same-genre item's quote.
        Checked same = new Checked("Amelie Lens", URL, QUOTE, FindingValidator.Type.SAME_GENRE_EVENT, 2);
        Checked big = new Checked("Rex Club", URL, "Rex Club: complet ce samedi 17 octobre 2026",
                FindingValidator.Type.BIG_EVENT, 2);
        for (boolean bigFirst : List.of(true, false)) {
            assertThat(questions(same, big, bigFirst)).as("bigFirst=" + bigFirst).containsExactly("2.1");
        }
    }

    @Test
    void bigEventWhoseQuoteNamesTheSameGenreEventCountsOnce() {
        // Only the same-genre name appears in the big item's quote, not the other way round.
        Checked same = new Checked("Amelie Lens", URL, QUOTE, FindingValidator.Type.SAME_GENRE_EVENT, 2);
        Checked big = new Checked("Nuit Blanche", URL,
                "Nuit Blanche : Amelie Lens en clôture, samedi 17 octobre 2026", FindingValidator.Type.BIG_EVENT, 2);
        for (boolean bigFirst : List.of(true, false)) {
            assertThat(questions(same, big, bigFirst)).as("bigFirst=" + bigFirst).containsExactly("2.1");
        }
    }

    @Test
    void bigEventSharingOnlyTheCityIsKept() {
        Checked same = new Checked("Amelie Lens", URL, "Amelie Lens au Rex Club Paris le samedi 17 octobre 2026",
                FindingValidator.Type.SAME_GENRE_EVENT, 2);
        Checked big = new Checked("Paris Saint-Germain - OM", URL,
                "Paris Saint-Germain - OM au Parc des Princes, samedi 17 octobre 2026", FindingValidator.Type.BIG_EVENT,
                2);
        assertThat(questions(same, big, false)).containsExactlyInAnyOrder("2.1", "5.3");
    }

    @Test
    void sameGenreLaterInTheWeekOnTheSamePageDoesNotHideTheBigEvent() {
        Checked same = new Checked("Amelie Lens", URL, "Amelie Lens au Rex Club le mardi 20 octobre 2026",
                FindingValidator.Type.SAME_GENRE_EVENT, 2);
        Checked big = new Checked("Amelie Lens", URL, "Rex Club: Amelie Lens, samedi 17 octobre 2026, complet",
                FindingValidator.Type.BIG_EVENT, 2);
        for (boolean bigFirst : List.of(true, false)) {
            assertThat(questions(same, big, bigFirst)).as("bigFirst=" + bigFirst)
                    .containsExactlyInAnyOrder("2.2", "5.3");
        }
    }

    @Test
    void unknownTypeDropped() {
        FindingValidator.Result res = check(item(QUOTE, "strike", 2), page(QUOTE));
        assertThat(res.kept()).isEmpty();
        assertThat(res.dropped()).containsEntry(Drop.UNKNOWN_TYPE, 1);
    }

    @Test
    void quoteWithoutDateDropped() {
        String quote = "Amelie Lens au Rex Club, bientôt";
        FindingValidator.Result res = check(item(quote), page(quote));
        assertThat(res.kept()).hasSize(1);
        assertThat(FindingValidator.assign(res.kept(), SAT_17_OCT, "Paris", BANK, FETCHED)).isEmpty();
    }

    @Test
    void lastYearQuoteDropped() {
        String quote = "Amelie Lens au Rex Club le vendredi 17 octobre 2025";
        assertThat(findings(item(quote), page(quote), SAT_17_OCT)).isEmpty();
    }

    @Test
    void lastYearsEditionNextToAYearlessDateDropped() {
        // The year-less "17 octobre" fits the night, but the page states last year's date.
        String quote = "Amelie Lens au Rex Club, 17 octobre (édition du vendredi 17 octobre 2025)";
        assertThat(findings(item(quote), page(quote), SAT_17_OCT)).isEmpty();
    }

    @Test
    void weekdayThatOnlyFitsAnEarlierYearDropped() {
        // 13 January is a Tuesday in 2026; the candidate night is in 2027.
        String quote = "Amelie Lens au Rex Club le mardi 13 janvier";
        assertThat(findings(item(quote), page(quote), LocalDate.of(2027, 1, 13))).isEmpty();
    }

    @Test
    void bigEventWithinANightIsQuestion53() {
        assertThat(findings(item(QUOTE, "big_event", 2), page(QUOTE), SAT_17_OCT)).singleElement().satisfies(f -> {
            assertThat(f.questionId()).isEqualTo("5.3");
            assertThat(f.window()).isEqualTo(Window.NIGHT);
            assertThat(f.weight()).isEqualTo(2);
        });
    }

    @Test
    void bigEventOutsideTheNightDropped() {
        String quote = "Amelie Lens au Rex Club le mercredi 21 octobre 2026";
        assertThat(findings(item(quote, "big_event", 2), page(quote), SAT_17_OCT)).isEmpty();
    }

    @Test
    void strengthAboveTwoClampedNotDropped() {
        assertThat(findings(item(QUOTE, "same_genre_event", 3), page(QUOTE), SAT_17_OCT))
                .extracting(Finding::strength).containsExactly(2);
        assertThat(findings(item(QUOTE, "same_genre_event", null), page(QUOTE), SAT_17_OCT))
                .extracting(Finding::strength).containsExactly(1);
        assertThat(findings(item(QUOTE, "same_genre_event", 0), page(QUOTE), SAT_17_OCT))
                .extracting(Finding::strength).containsExactly(1);
    }

    @Test
    void webFindingNeverStopFactor() {
        String week = "Amelie Lens au Rex Club le mercredi 21 octobre 2026";
        List<Finding> out = FindingValidator.assign(List.of(
                new Checked("Amelie Lens", URL, QUOTE, FindingValidator.Type.SAME_GENRE_EVENT, 2),
                new Checked("Amelie Lens", URL + "/b", week, FindingValidator.Type.SAME_GENRE_EVENT, 2),
                new Checked("Fête des Lumières", URL + "/c",
                        "Fête des Lumières sur les quais le samedi 17 octobre 2026", FindingValidator.Type.BIG_EVENT, 2)),
                SAT_17_OCT, "Paris",
                BANK, FETCHED);
        assertThat(out).extracting(Finding::questionId).containsExactly("2.1", "2.2", "5.3");
        assertThat(out).allSatisfy(f -> {
            assertThat(f.stopFactor()).isFalse();
            assertThat(f.sourceKind()).isEqualTo(SourceKind.WEB);
            assertThat(f.strength()).isLessThanOrEqualTo(2);
        });
    }
}
