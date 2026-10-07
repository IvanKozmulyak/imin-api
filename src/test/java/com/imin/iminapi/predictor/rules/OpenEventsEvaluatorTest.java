package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.model.GenreWeekCount;
import com.imin.iminapi.predictor.model.OpenEventOccurrence;
import com.imin.iminapi.predictor.repository.GenreWeekCountRepository;
import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.openevents.GenreMatcher;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.City;
import com.imin.iminapi.predictor.sources.openevents.OpenEventSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.imin.iminapi.predictor.rules.RuleFixtures.BANK;
import static com.imin.iminapi.predictor.rules.RuleFixtures.TODAY;
import static com.imin.iminapi.predictor.rules.RuleFixtures.in;
import static com.imin.iminapi.predictor.rules.RuleFixtures.q;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenEventsEvaluatorTest {

    private static final String HOUSE = "house & techno";
    private static final String LO = "Licence Ouverte 2.0";
    private static final String OA_CREDIT =
            "Agendas : Ville de Lille, Métropole Européenne de Lille (openagenda.com), Licence Ouverte 2.0";
    private static final LocalDate THIS_MONDAY = LocalDate.of(2026, 9, 28);
    /** Saturday of the week starting 2026-10-05. */
    private static final LocalDate SAT = LocalDate.of(2026, 10, 10);
    private static final LocalDate THU = LocalDate.of(2026, 10, 8);
    private static final String PARAMS_2_6 = "params: { busy_ratio: 1.5, strong_ratio: 2.0, min_excess: 2, max_ahead_days: 28 }";

    /** A source that only names itself, its gate and licence, and the cities it covers. */
    private record FakeSource(String id, String gate, String licence, String coveredCity) implements OpenEventSource {
        @Override public boolean backfills() { return true; }
        @Override public boolean covers(City city) { return city.key().equals(coveredCity); }
        @Override public Fetch fetch(City city, LocalDate from, LocalDate to) { throw new UnsupportedOperationException(); }
    }

    private static final OpenEventSource OPENAGENDA = new FakeSource("openagenda", "openagenda", LO, "lille");
    private static final OpenEventSource QFAP = new FakeSource("quefaireaparis", "quefaireaparis", "ODbL 1.0", "paris");

    private final GenreWeekCountRepository counts = mock(GenreWeekCountRepository.class);
    private final OpenEventOccurrenceRepository occurrences = mock(OpenEventOccurrenceRepository.class);
    private final SourceGates gates = mock(SourceGates.class);
    private final OpenEventCities cities = OpenEventCities.load(new DefaultResourceLoader());
    private DataSourceCatalog catalog;
    private final List<GenreWeekCount> weekRows = new ArrayList<>();
    private final List<OpenEventOccurrence> rows = new ArrayList<>();
    private final Question q26 = q("2.6", SourceKind.STRUCTURED);
    private final Question q53 = q("5.3", SourceKind.STRUCTURED);
    private final Question q23 = q("2.3", SourceKind.STRUCTURED);

    @BeforeEach
    void setUp() {
        when(gates.keys()).thenReturn(Set.of("date-check", "weather", "wikimedia", "football", "openagenda", "quefaireaparis"));
        when(gates.isOn(anyString())).thenReturn(true);
        catalog = DataSourceCatalog.load(new DefaultResourceLoader(), gates);
        assertThat(TODAY.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(SAT.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(THU.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        synced(Instant.parse("2026-09-28T03:45:00Z"));
        when(counts.findByCityKeyAndGenreFamilyAndSubGenreAndWeekStartBetween(anyString(), anyString(), eq(""),
                any(LocalDate.class), any(LocalDate.class))).thenAnswer(a -> weekRows.stream()
                .filter(c -> c.getCityKey().equals(a.getArgument(0)) && c.getGenreFamily().equals(a.getArgument(1)))
                .filter(c -> !c.getWeekStart().isBefore(a.getArgument(3)) && !c.getWeekStart().isAfter(a.getArgument(4)))
                .toList());
        when(occurrences.findByCityKeyAndNightDateBetween(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(a -> rows.stream()
                        .filter(o -> o.getCityKey().equals(a.getArgument(0)))
                        .filter(o -> !o.getNightDate().isBefore(a.getArgument(1)) && !o.getNightDate().isAfter(a.getArgument(2)))
                        .toList());
    }

    private OpenEventsEvaluator evaluator() {
        return new OpenEventsEvaluator(BANK, cities, List.of(OPENAGENDA, QFAP), counts, occurrences, gates, catalog);
    }

    private void synced(Instant updatedAt) {
        GenreWeekCount latest = new GenreWeekCount();
        latest.setCityKey("lille");
        latest.setUpdatedAt(updatedAt);
        when(counts.findTopByCityKeyOrderByUpdatedAtDesc("lille")).thenReturn(Optional.of(latest));
    }

    private static DateCheckInput lille() {
        return city("Lille");
    }

    private static DateCheckInput city(String name) {
        return in().city(name, "FR", null).genre(HOUSE, null).build();
    }

    private Finding one(Question q, DateCheckInput input, LocalDate d) {
        return evaluator().evaluate(q, input, d);
    }

    private List<Finding> all(DateCheckInput input, LocalDate d) {
        return evaluator().evaluateAll(List.of(q26, q53, q23), input, d);
    }

    private static String reason(Finding f) {
        assertThat(f.status()).isEqualTo(Finding.Status.NOT_CHECKED);
        return (String) f.facts().get("reason");
    }

    private void week(LocalDate weekStart, int count, String sourcesJson) {
        GenreWeekCount c = new GenreWeekCount();
        c.setCityKey("lille");
        c.setGenreFamily(HOUSE);
        c.setSubGenre("");
        c.setWeekStart(weekStart);
        c.setEventCount(count);
        c.setSourcesJson(sourcesJson);
        c.setUpdatedAt(Instant.parse("2026-09-28T03:45:00Z"));
        weekRows.add(c);
    }

    private void week(LocalDate weekStart, int count) {
        week(weekStart, count, "[{\"source\":\"openagenda\",\"licence\":\"" + LO + "\",\"events\":" + count + "}]");
    }

    /** Counts for the 12 ISO weeks 2026-07-06 … 2026-09-21, oldest first. */
    private void history(int... values) {
        assertThat(values).hasSize(12);
        for (int i = 0; i < 12; i++) week(THIS_MONDAY.minusWeeks(12 - i), values[i]);
        assertThat(THIS_MONDAY.minusWeeks(12)).isEqualTo(LocalDate.of(2026, 7, 6));
    }

    private void busyHistory() {
        history(2, 2, 3, 3, 3, 3, 4, 4, 4, 5, 5, 6);
    }

    private OpenEventOccurrence row(String source, LocalDate night, String title, boolean community, String... genres) {
        OpenEventOccurrence o = new OpenEventOccurrence();
        o.setSource(source);
        o.setSourceEventId(title + night);
        o.setCityKey("lille");
        o.setNightDate(night);
        o.setTitle(title);
        o.setTitleKey(GenreMatcher.normalise(title));
        o.setUrl("https://openagenda.com/fr/ville-de-lille/events/" + GenreMatcher.normalise(title).replace(' ', '-')
                + "-" + night);
        o.setGenreKeys(genres.length == 0 ? "[]" : "[\"" + String.join("\",\"", genres) + "\"]");
        o.setCommunity(community);
        o.setLicence(source.equals("openagenda") ? LO : "ODbL 1.0");
        o.setSyncedAt(Instant.parse("2026-09-28T03:45:00Z"));
        rows.add(o);
        return o;
    }

    private void party(String title, LocalDate... nights) {
        for (LocalDate n : nights) row("openagenda", n, title, false, HOUSE);
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    // --- shared checks ---

    @Test
    void blankCityIsNotProvided() {
        List<Finding> out = all(city(" "), SAT);

        assertThat(out).extracting(OpenEventsEvaluatorTest::reason).containsExactly("not_provided", "not_provided", "not_provided");
        verifyNoInteractions(counts, occurrences);
    }

    @Test
    void cityWithoutSourceIsNoSource() {
        List<Finding> out = all(city("Metz"), SAT);

        assertThat(out).extracting(OpenEventsEvaluatorTest::reason).containsExactly("no_source", "no_source", "no_source");
        verifyNoInteractions(counts, occurrences);
    }

    @Test
    void sameNameInAnotherCountryIsNoSource() {
        DateCheckInput berlinParis = in().city("Paris", "DE", null).genre(HOUSE, null).build();

        assertThat(all(berlinParis, SAT)).extracting(OpenEventsEvaluatorTest::reason)
                .containsExactly("no_source", "no_source", "no_source");
        verifyNoInteractions(counts, occurrences);
    }

    @Test
    void aliasResolvesToCity() {
        row("openagenda", SAT, "Braderie de Lomme", true);

        Finding f = one(q53, city("Lomme"), SAT);

        assertThat(f.status()).isEqualTo(Finding.Status.FOUND);
        verify(occurrences).findByCityKeyAndNightDateBetween(eq("lille"), any(), any());
        verify(counts).findTopByCityKeyOrderByUpdatedAtDesc("lille");
    }

    @Test
    void sourceGateOffIsSourceOff() {
        when(gates.isOn("openagenda")).thenReturn(false);
        List<Question> bankOrder = BANK.questions().stream()
                .filter(x -> evaluator().questionIds().contains(x.id()) && x.source() == SourceKind.STRUCTURED)
                .toList();

        List<Finding> out = evaluator().evaluateAll(bankOrder, lille(), SAT);

        assertThat(out).extracting(Finding::questionId).containsExactly("2.6", "5.3", "2.3");
        assertThat(out).extracting(OpenEventsEvaluatorTest::reason).containsOnly("source_off");
        verifyNoInteractions(counts, occurrences);
        // Paris is read from another source, whose gate is on.
        assertThat(one(q53, city("Paris"), SAT).facts()).doesNotContainEntry("reason", "source_off");
    }

    @Test
    void beyondMaxAheadIsTooFarAhead() {
        busyHistory();
        week(d("2026-10-26"), 3);

        assertThat(reason(one(q26, lille(), TODAY.plusDays(29)))).isEqualTo("too_far_ahead");
        assertThat(one(q26, lille(), TODAY.plusDays(28)).status()).isEqualTo(Finding.Status.CLEAR);
        assertThat(reason(one(q53, lille(), TODAY.plusDays(61)))).isEqualTo("too_far_ahead");
        assertThat(one(q53, lille(), TODAY.plusDays(60)).status()).isEqualTo(Finding.Status.CLEAR);
        assertThat(reason(one(q23, lille(), TODAY.plusDays(91)))).isEqualTo("too_far_ahead");
        assertThat(one(q23, lille(), TODAY.plusDays(90)).status()).isEqualTo(Finding.Status.CLEAR);
    }

    @Test
    void noCountsIsNotSynced() {
        when(counts.findTopByCityKeyOrderByUpdatedAtDesc("lille")).thenReturn(Optional.empty());

        assertThat(all(lille(), SAT)).extracting(OpenEventsEvaluatorTest::reason)
                .containsExactly("not_synced", "not_synced", "not_synced");
        verify(counts, times(1)).findTopByCityKeyOrderByUpdatedAtDesc("lille");
        verify(occurrences, never()).findByCityKeyAndNightDateBetween(anyString(), any(), any());
    }

    @Test
    void oldCountsAreStale() {
        busyHistory();
        week(d("2026-10-05"), 3);

        synced(Instant.parse("2026-09-15T12:00:00Z"));
        assertThat(all(lille(), SAT)).extracting(OpenEventsEvaluatorTest::reason).containsExactly("stale", "stale", "stale");

        synced(Instant.parse("2026-09-16T12:00:00Z"));
        assertThat(one(q26, lille(), SAT).status()).isEqualTo(Finding.Status.CLEAR);

        // 22:30 UTC on the 15th is already the 16th in Paris
        synced(Instant.parse("2026-09-15T22:30:00Z"));
        assertThat(one(q26, lille(), SAT).status()).isEqualTo(Finding.Status.CLEAR);
    }

    @Test
    void fewerThan12WeeksIsNoData() {
        busyHistory();
        weekRows.removeIf(c -> c.getWeekStart().equals(d("2026-07-06")));
        week(d("2026-10-05"), 8);
        party("Techno Thursday", d("2026-09-10"), d("2026-09-17"), d("2026-09-24"));
        row("openagenda", THU, "Carnaval", true);

        assertThat(reason(one(q26, lille(), SAT))).isEqualTo("no_data");
        assertThat(reason(one(q23, lille(), THU))).isEqualTo("no_data");
        assertThat(one(q53, lille(), THU).status()).isEqualTo(Finding.Status.FOUND);
    }

    @Test
    void missingCandidateWeekIsNoData() {
        busyHistory();

        assertThat(reason(one(q26, lille(), SAT))).isEqualTo("no_data");
    }

    // --- 2.6 ---

    @Test
    void busyWeekIsRiskStrength1() {
        busyHistory();
        week(d("2026-10-05"), 6);

        Finding f = one(q26, lille(), SAT);

        assertThat(f.status()).isEqualTo(Finding.Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(1);
        assertThat(f.sourceKind()).isEqualTo(SourceKind.STRUCTURED);
        assertThat(f.facts()).containsEntry("weekStart", "2026-10-05").containsEntry("count", 6)
                .containsEntry("norm", 3.5).containsEntry("normWeeks", 12);
        assertThat(f.url()).isNull();
    }

    private static int[] flat(int n) {
        int[] v = new int[12];
        java.util.Arrays.fill(v, n);
        return v;
    }

    private static final int[] BUSY = {2, 2, 3, 3, 3, 3, 4, 4, 4, 5, 5, 6};

    /** The candidate week's count against a 12-week norm; {@code null} expectations are not asserted. */
    static Stream<Arguments> candidateWeeks() {
        return Stream.of(
                Arguments.of("very busy week", BUSY, 8, Finding.Status.FOUND, 2, null),
                Arguments.of("whole norm stays whole", flat(4), 8, Finding.Status.FOUND, 2, 4L),
                Arguments.of("below ratio", BUSY, 5, Finding.Status.CLEAR, null, null),
                Arguments.of("zero norm under min excess", flat(0), 1, Finding.Status.CLEAR, null, null),
                Arguments.of("zero norm at min excess", flat(0), 2, Finding.Status.FOUND, 1, 0L),
                Arguments.of("zero norm with double excess", flat(0), 4, Finding.Status.FOUND, 2, null),
                // 15 is 1.5 x 10 but under 2 x 10, though 5 above the norm clears 2 x min_excess
                Arguments.of("ratio on top of excess, busy", flat(10), 15, Finding.Status.FOUND, 1, null),
                // 13 is under 1.5 x 10, though 3 above the norm clears min_excess
                Arguments.of("ratio on top of excess, under ratio", flat(10), 13, Finding.Status.CLEAR, null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("candidateWeeks")
    void candidateWeekAgainstNorm(String name, int[] norm, int count, Finding.Status status, Integer strength,
                                  Long normFact) {
        history(norm);
        week(d("2026-10-05"), count);

        Finding f = one(q26, lille(), SAT);

        assertThat(f.status()).isEqualTo(status);
        if (status == Finding.Status.FOUND) assertThat(f.facts()).containsEntry("count", count);
        if (strength != null) assertThat(f.strength()).isEqualTo(strength);
        if (normFact != null) assertThat(f.facts()).containsEntry("norm", normFact);
    }

    @Test
    void malformedSourcesJsonIsNoData() {
        busyHistory();
        week(d("2026-10-05"), 8, "{not json");

        assertThat(reason(one(q26, lille(), SAT))).isEqualTo("no_data");
        // the other questions still answer
        row("openagenda", SAT, "Carnaval", true);
        assertThat(one(q53, lille(), SAT).status()).isEqualTo(Finding.Status.FOUND);
    }

    @Test
    void sourcesFactCarriesLicenceUrlCredit() {
        busyHistory();
        week(d("2026-10-05"), 6, "[{\"source\":\"openagenda\",\"licence\":\"" + LO + "\",\"events\":6},"
                + "{\"source\":\"quefaireaparis\",\"licence\":\"ODbL 1.0\",\"events\":0}]");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) one(q26, lille(), SAT).facts().get("sources");

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0)).containsEntry("source", "openagenda").containsEntry("licence", LO)
                .containsEntry("events", 6).containsEntry("url", "https://openagenda.com/").containsEntry("credit", OA_CREDIT);
        assertThat(sources.get(1)).containsEntry("source", "quefaireaparis").containsEntry("events", 0)
                .containsEntry("url", "https://opendata.paris.fr/explore/dataset/que-faire-a-paris-/");
    }

    // --- 5.3 ---

    @Test
    void communityOnNightIsStrength2() {
        OpenEventOccurrence carnival = row("openagenda", SAT, "Carnaval de Lille", true);
        row("openagenda", SAT, "Zumba géante", true);

        Finding f = one(q53, lille(), SAT);

        assertThat(f.status()).isEqualTo(Finding.Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("date", "2026-10-10").containsEntry("name", "Carnaval de Lille")
                .containsEntry("count", 2).containsEntry("source", "openagenda").containsEntry("licence", LO)
                .containsEntry("credit", OA_CREDIT);
        assertThat(f.url()).isEqualTo(carnival.getUrl());
    }

    /** One community (or non-community) row {@code offset} nights from the date. */
    private record Night(int offset, String title, boolean community) { }

    static Stream<Arguments> communityNights() {
        return Stream.of(
                Arguments.of("next night", List.of(new Night(1, "Braderie", true)), Finding.Status.FOUND, 1, "2026-10-11"),
                Arguments.of("night before", List.of(new Night(-1, "Braderie", true)), Finding.Status.FOUND, 1, "2026-10-09"),
                Arguments.of("two nights away", List.of(new Night(2, "Braderie", true), new Night(-2, "Carnaval", true)),
                        Finding.Status.CLEAR, null, null),
                Arguments.of("non-community row ignored", List.of(new Night(0, "Techno party", false)),
                        Finding.Status.CLEAR, null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("communityNights")
    void communityNearTheDate(String name, List<Night> nights, Finding.Status status, Integer strength, String date) {
        for (Night n : nights) {
            if (n.community()) row("openagenda", SAT.plusDays(n.offset()), n.title(), true);
            else row("openagenda", SAT.plusDays(n.offset()), n.title(), false, HOUSE);
        }

        Finding f = one(q53, lille(), SAT);

        assertThat(f.status()).isEqualTo(status);
        if (strength != null) assertThat(f.strength()).isEqualTo(strength);
        if (date != null) assertThat(f.facts()).containsEntry("date", date);
    }

    @Test
    void nearestNightChosenOverTitle() {
        row("openagenda", SAT.plusDays(1), "Aaa braderie", true);
        row("openagenda", SAT, "Zzz carnaval", true);

        assertThat(one(q53, lille(), SAT).facts()).containsEntry("name", "Zzz carnaval").containsEntry("count", 2);
    }

    @Test
    void communityDedupedAcrossSources() {
        row("quefaireaparis", SAT, "Carnaval", true);
        row("openagenda", SAT, "Carnaval", true);

        Finding f = one(q53, lille(), SAT);

        assertThat(f.facts()).containsEntry("count", 1).containsEntry("source", "openagenda");
    }

    // --- 2.3 ---

    @Test
    void weeklySeriesPredicted() {
        busyHistory();
        party("Techno Thursday", d("2026-09-10"), d("2026-09-17"), d("2026-09-24"));

        Finding f = one(q23, lille(), THU);

        assertThat(f.status()).isEqualTo(Finding.Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(1);
        assertThat(f.facts()).containsEntry("name", "Techno Thursday").containsEntry("pattern", "weekly")
                .containsEntry("weekday", "thursday").containsEntry("count", 3).containsEntry("lastDate", "2026-09-24")
                .containsEntry("announced", false).containsEntry("seriesCount", 1).containsEntry("source", "openagenda")
                .containsEntry("licence", LO).containsEntry("credit", OA_CREDIT).doesNotContainKey("ordinal");
        assertThat(f.url()).endsWith("-2026-09-24");
    }

    @Test
    void lastWeekdayOfMonthPredicted() {
        busyHistory();
        LocalDate d = d("2026-10-30");
        party("Last Friday", d("2026-07-31"), d("2026-08-28"), d("2026-09-25"));
        assertThat(d.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);

        Finding f = one(q23, lille(), d);

        assertThat(f.facts()).containsEntry("pattern", "monthly_last").containsEntry("weekday", "friday")
                .containsEntry("count", 3).containsEntry("lastDate", "2026-09-25").doesNotContainKey("ordinal");
    }

    @Test
    void nthWeekdayOfMonthPredicted() {
        busyHistory();
        party("Second Saturday", d("2026-07-11"), d("2026-08-08"), d("2026-09-12"));

        Finding f = one(q23, lille(), SAT);

        assertThat(f.facts()).containsEntry("pattern", "monthly_nth").containsEntry("weekday", "saturday")
                .containsEntry("ordinal", 2).containsEntry("count", 3).containsEntry("lastDate", "2026-09-12");
    }

    @Test
    void monthlyBeyondGapIsClear() {
        busyHistory();
        // nth: latest 2026-08-08 is 63 days before 10-10 and fits; 07-11 alone would be 91
        party("Second Saturday", d("2026-05-09"), d("2026-06-13"), d("2026-07-11"));

        assertThat(one(q23, lille(), SAT).status()).isEqualTo(Finding.Status.CLEAR);
        party("Second Saturday", d("2026-08-08"));
        assertThat(one(q23, lille(), SAT).facts()).containsEntry("count", 4).containsEntry("lastDate", "2026-08-08");
    }

    @Test
    void announcedSeriesIsStrength2() {
        busyHistory();
        party("Techno Thursday", d("2026-09-10"), d("2026-09-17"), d("2026-09-24"), THU);

        Finding f = one(q23, lille(), THU);

        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("announced", true).containsEntry("count", 3)
                .containsEntry("lastDate", "2026-09-24");
        assertThat(f.url()).endsWith("-2026-10-08");
    }

    @Test
    void announcedSeriesMayContinueAfterTheDate() {
        busyHistory();
        party("Techno Thursday", d("2026-09-17"), d("2026-09-24"), d("2026-10-01"), THU, d("2026-10-15"),
                d("2026-10-22"), d("2026-10-29"));

        Finding f = one(q23, lille(), THU);

        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("pattern", "weekly").containsEntry("count", 3)
                .containsEntry("lastDate", "2026-10-01");
    }

    @Test
    void monthlySeriesSkippingTheDateIsClear() {
        busyHistory();
        party("Second Saturday", d("2026-07-11"), d("2026-08-08"), d("2026-09-12"), d("2026-11-14"));
        assertThat(one(q23, lille(), SAT).status()).isEqualTo(Finding.Status.CLEAR);

        party("Second Saturday", SAT);
        Finding f = one(q23, lille(), SAT);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("pattern", "monthly_nth").containsEntry("lastDate", "2026-09-12");
    }

    /** House nights that do not make a series predicting the date. */
    static Stream<Arguments> notASeries() {
        return Stream.of(
                Arguments.of("weekly series skipping the date", "Techno Thursday",
                        List.of("2026-09-10", "2026-09-17", "2026-09-24", "2026-10-01", "2026-10-15", "2026-10-22"), THU),
                Arguments.of("two weekly occurrences", "Techno Thursday", List.of("2026-09-17", "2026-09-24"), THU),
                Arguments.of("two monthly occurrences", "Second Saturday", List.of("2026-08-08", "2026-09-12"), SAT),
                Arguments.of("other weekday", "Techno Wednesday", List.of("2026-09-09", "2026-09-16", "2026-09-23"), THU),
                Arguments.of("other ordinals", "Some Saturday", List.of("2026-07-04", "2026-08-08", "2026-09-19"), SAT),
                // a last-Friday series does not predict the fourth Friday 10-23, which is not the last
                Arguments.of("last-of-month on a non-last date", "Last Friday",
                        List.of("2026-07-31", "2026-08-28", "2026-09-25"), d("2026-10-23")),
                // fourth Fridays that were not the last do not predict the last Friday 10-30
                Arguments.of("non-last nights on a last date", "Fourth Friday",
                        List.of("2026-07-24", "2026-08-21", "2026-09-18"), d("2026-10-30")),
                Arguments.of("every other week", "Fortnightly", List.of("2026-08-27", "2026-09-10", "2026-09-24"), THU),
                // second Thursdays of Jul, Aug, Sep, plus a fourth Thursday in July
                Arguments.of("two nights in one month", "Thursday Club",
                        List.of("2026-07-09", "2026-07-23", "2026-08-13", "2026-09-10"), THU),
                // last Thursdays of Jun, Jul, Aug, then 09-03 a week later: one night per month, but weekly-shaped
                Arguments.of("seven days apart across months", "Thursday Club",
                        List.of("2026-06-25", "2026-07-30", "2026-08-27", "2026-09-03"), d("2026-10-29")),
                Arguments.of("series only after the date", "Autumn Thursday",
                        List.of("2026-10-15", "2026-10-22", "2026-10-29"), THU));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notASeries")
    void notASeriesIsClear(String name, String title, List<String> nights, LocalDate date) {
        busyHistory();
        party(title, nights.stream().map(LocalDate::parse).toArray(LocalDate[]::new));

        assertThat(one(q23, lille(), date).status()).isEqualTo(Finding.Status.CLEAR);
    }

    @Test
    void endedWeeklySeriesIsNotMonthly() {
        busyHistory();
        List<LocalDate> thursdays = new ArrayList<>();
        for (LocalDate n = d("2026-06-04"); !n.isAfter(d("2026-08-27")); n = n.plusWeeks(1)) thursdays.add(n);
        party("Summer Thursday", thursdays.toArray(LocalDate[]::new));

        // its second Thursdays (06-11, 07-09, 08-13) and last Thursdays (06-25, 07-30, 08-27) are not a monthly series
        assertThat(one(q23, lille(), THU).status()).isEqualTo(Finding.Status.CLEAR);
        LocalDate lastThursday = d("2026-10-29");
        assertThat(lastThursday.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(one(q23, lille(), lastThursday).status()).isEqualTo(Finding.Status.CLEAR);
    }

    @Test
    void staleSeriesBeyondGapIsClear() {
        busyHistory();
        party("Summer Thursday", d("2026-08-06"), d("2026-08-13"), d("2026-08-20"));
        assertThat(one(q23, lille(), THU).status()).isEqualTo(Finding.Status.CLEAR);

        // 28 days before the date is still within the gap
        party("Summer Thursday", d("2026-08-27"), d("2026-09-03"), d("2026-09-10"));
        assertThat(one(q23, lille(), THU).facts()).containsEntry("lastDate", "2026-09-10").containsEntry("count", 6);
    }

    @Test
    void otherGenreSeriesIgnored() {
        busyHistory();
        for (String n : List.of("2026-09-10", "2026-09-17", "2026-09-24")) row("openagenda", d(n), "Pop Thursday", false, "pop");

        assertThat(one(q23, lille(), THU).status()).isEqualTo(Finding.Status.CLEAR);
    }

    @Test
    void seriesDedupedByTitleKeyAcrossSources() {
        busyHistory();
        row("quefaireaparis", d("2026-09-10"), "TECHNO Thursday!", false, HOUSE);
        row("openagenda", d("2026-09-17"), "Techno thursday", false, HOUSE);
        row("quefaireaparis", d("2026-09-24"), "TECHNO Thursday!", false, HOUSE);
        row("openagenda", d("2026-09-24"), "Techno thursday", false, HOUSE);

        Finding f = one(q23, lille(), THU);

        assertThat(f.facts()).containsEntry("count", 3).containsEntry("seriesCount", 1)
                .containsEntry("source", "openagenda").containsEntry("name", "Techno thursday");
    }

    @Test
    void chosenSeriesPrefersAnnouncedThenLongerThenTitle() {
        busyHistory();
        party("Bravo", d("2026-09-03"), d("2026-09-10"), d("2026-09-17"), d("2026-09-24"));
        party("Alpha", d("2026-09-10"), d("2026-09-17"), d("2026-09-24"));
        party("Charlie", d("2026-09-10"), d("2026-09-17"), d("2026-09-24"));
        assertThat(one(q23, lille(), THU).facts()).containsEntry("name", "Bravo").containsEntry("seriesCount", 3);

        party("Charlie", THU);
        assertThat(one(q23, lille(), THU).facts()).containsEntry("name", "Charlie").containsEntry("announced", true);

        rows.removeIf(o -> o.getTitle().equals("Bravo") || o.getTitle().equals("Charlie"));
        party("Delta", d("2026-09-10"), d("2026-09-17"), d("2026-09-24"));
        assertThat(one(q23, lille(), THU).facts()).containsEntry("name", "Alpha");
    }

    @Test
    void oneReadPerTablePerDate() {
        busyHistory();
        week(d("2026-10-05"), 6);

        all(lille(), SAT);

        verify(counts, times(1)).findTopByCityKeyOrderByUpdatedAtDesc("lille");
        verify(counts, times(1)).findByCityKeyAndGenreFamilyAndSubGenreAndWeekStartBetween(
                "lille", HOUSE, "", d("2026-07-06"), d("2026-10-05"));
        verify(occurrences, times(1)).findByCityKeyAndNightDateBetween("lille", d("2026-03-14"), d("2027-01-28"));
    }

    // --- boot ---

    /** The shipped bank with each (find, replace) pair applied once. */
    private static QuestionBank bankWith(String... findThenReplace) throws IOException {
        String bank;
        try (InputStream in = OpenEventsEvaluatorTest.class.getResourceAsStream("/predictor/question-bank-v2.yaml")) {
            bank = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (int i = 0; i < findThenReplace.length; i += 2) {
            assertThat(bank).contains(findThenReplace[i]);
            bank = bank.replace(findThenReplace[i], findThenReplace[i + 1]);
        }
        return QuestionBankLoader.parse(new ByteArrayInputStream(bank.getBytes(StandardCharsets.UTF_8)),
                OpenEventsEvaluatorTest.class.getResourceAsStream("/predictor/genre-profiles-v1.yaml"));
    }

    private OpenEventsEvaluator build(QuestionBank bank) {
        return new OpenEventsEvaluator(bank, cities, List.of(OPENAGENDA, QFAP), counts, occurrences, gates, catalog);
    }

    @Test
    void claimsThreeStructuredQuestions() {
        assertThat(evaluator().source()).isEqualTo(SourceKind.STRUCTURED);
        assertThat(evaluator().questionIds()).containsExactlyInAnyOrder("2.6", "5.3", "2.3");
    }

    private static final String PARAMS_2_3 =
            "params: { min_occurrences: 3, weekly_max_gap_days: 28, monthly_max_gap_days: 70, max_ahead_days: 90 }";

    /** Bank edits (find, replace pairs) that must fail boot, and the message fragments they name. */
    static Stream<Arguments> badBanks() {
        return Stream.of(
                Arguments.of("unknown param", new String[] {PARAMS_2_6, PARAMS_2_6.replace(" }", ", foo: 1 }")},
                        List.of("2.6", "params.foo")),
                Arguments.of("max ahead over lookahead",
                        new String[] {"params: { max_ahead_days: 60 }", "params: { max_ahead_days: 121 }"},
                        List.of("5.3", "params.max_ahead_days")),
                Arguments.of("busy_ratio low", new String[] {PARAMS_2_6, PARAMS_2_6.replace("busy_ratio: 1.5", "busy_ratio: 1")},
                        List.of("params.busy_ratio")),
                Arguments.of("busy_ratio high", new String[] {PARAMS_2_6, PARAMS_2_6.replace("busy_ratio: 1.5", "busy_ratio: 5.5")},
                        List.of("params.busy_ratio")),
                Arguments.of("strong_ratio low",
                        new String[] {PARAMS_2_6, PARAMS_2_6.replace("strong_ratio: 2.0", "strong_ratio: 1.4")},
                        List.of("params.strong_ratio")),
                Arguments.of("strong_ratio high",
                        new String[] {PARAMS_2_6, PARAMS_2_6.replace("strong_ratio: 2.0", "strong_ratio: 10.5")},
                        List.of("params.strong_ratio")),
                Arguments.of("min_excess fractional",
                        new String[] {PARAMS_2_6, PARAMS_2_6.replace("min_excess: 2", "min_excess: 1.5")},
                        List.of("params.min_excess")),
                Arguments.of("min_excess high", new String[] {PARAMS_2_6, PARAMS_2_6.replace("min_excess: 2", "min_excess: 21")},
                        List.of("params.min_excess")),
                Arguments.of("min_occurrences low",
                        new String[] {PARAMS_2_3, PARAMS_2_3.replace("min_occurrences: 3", "min_occurrences: 1")},
                        List.of("params.min_occurrences")),
                Arguments.of("weekly_max_gap_days low",
                        new String[] {PARAMS_2_3, PARAMS_2_3.replace("weekly_max_gap_days: 28", "weekly_max_gap_days: 6")},
                        List.of("params.weekly_max_gap_days")),
                Arguments.of("monthly_max_gap_days high",
                        new String[] {PARAMS_2_3, PARAMS_2_3.replace("monthly_max_gap_days: 70", "monthly_max_gap_days: 121")},
                        List.of("params.monthly_max_gap_days")),
                Arguments.of("max_ahead_days zero",
                        new String[] {PARAMS_2_3, PARAMS_2_3.replace("max_ahead_days: 90", "max_ahead_days: 0")},
                        List.of("params.max_ahead_days")),
                Arguments.of("missing param",
                        new String[] {PARAMS_2_6, "params: { busy_ratio: 1.5, strong_ratio: 2.0, max_ahead_days: 28 }"},
                        List.of("min_excess")),
                Arguments.of("question missing from bank", new String[] {
                                "  - id: \"5.3\"\n    family: communities\n", "  - id: \"5.4\"\n    family: communities\n",
                                "template: predictor.q.5_3", "template: predictor.q.5_4"},
                        List.of("5.3", "not in the bank")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badBanks")
    void badBankFailsBoot(String name, String[] findThenReplace, List<String> fragments) throws IOException {
        QuestionBank bank = bankWith(findThenReplace);

        assertThatThrownBy(() -> build(bank)).isInstanceOf(IllegalStateException.class)
                .satisfies(e -> fragments.forEach(f -> assertThat(e).hasMessageContaining(f)));
    }

    @Test
    void inclusiveParamEdgesLoad() throws IOException {
        build(bankWith("params: { max_ahead_days: 60 }", "params: { max_ahead_days: 120 }"));
        build(bankWith(PARAMS_2_6, PARAMS_2_6.replace("busy_ratio: 1.5", "busy_ratio: 2.0")));
        build(bankWith(PARAMS_2_3, PARAMS_2_3.replace("min_occurrences: 3", "min_occurrences: 10")));
    }

    @Test
    void sourceWithoutCatalogEntryFailsBoot() {
        OpenEventSource datatourisme = new FakeSource("datatourisme", "datatourisme", LO, "lille");
        assertThatThrownBy(() -> new OpenEventsEvaluator(BANK, cities, List.of(OPENAGENDA, datatourisme), counts,
                occurrences, gates, catalog)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("datatourisme has no sources.yaml entry");

        OpenEventSource odbl = new FakeSource("openagenda", "openagenda", "ODbL 1.0", "lille");
        assertThatThrownBy(() -> new OpenEventsEvaluator(BANK, cities, List.of(odbl), counts, occurrences, gates, catalog))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("licence mismatch");
    }

    @Test
    void sourceWithoutMatchingSyncSourceFailsBoot() {
        String entry = """
                reviewedOn: 2026-10-01
                sources:
                  - id: openagenda
                    name: OpenAgenda
                    usedFor: [local_events]
                    licence: Licence Ouverte 2.0
                    licenceUrl: https://www.etalab.gouv.fr/licence-ouverte-open-licence/
                    creditLine: OpenAgenda, Licence Ouverte 2.0
                    url: https://openagenda.com/
                    gate: openagenda
                """;
        for (String yaml : List.of(entry, entry.replace("    gate: openagenda", "    syncSource: quefaireaparis\n    gate: openagenda"))) {
            DataSourceCatalog inline = DataSourceCatalog.parse(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), gates);
            assertThatThrownBy(() -> new OpenEventsEvaluator(BANK, cities, List.of(OPENAGENDA), counts, occurrences,
                    gates, inline)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("needs syncSource: openagenda");
        }
    }
}
