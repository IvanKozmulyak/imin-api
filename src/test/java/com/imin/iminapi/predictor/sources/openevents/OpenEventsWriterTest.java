package com.imin.iminapi.predictor.sources.openevents;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.model.GenreWeekCount;
import com.imin.iminapi.predictor.model.OpenEventOccurrence;
import com.imin.iminapi.predictor.repository.GenreWeekCountRepository;
import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter.Row;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter.Written;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional
class OpenEventsWriterTest {

    private static final Instant T1 = Instant.parse("2026-09-28T03:45:00Z");
    private static final Instant T2 = Instant.parse("2026-10-05T03:45:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate WEEK = LocalDate.of(2026, 9, 28);
    private static final String LO = "Licence Ouverte 2.0";
    private static final String ODBL = "ODbL 1.0";
    private static final String HOUSE = "house & techno";

    @Autowired OpenEventsWriter writer;
    @Autowired OpenEventOccurrenceRepository occurrences;
    @Autowired GenreWeekCountRepository counts;
    @Autowired EntityManager em;

    private static Row row(String id, LocalDate night, String title, Set<String> genres) {
        return new Row(id, night, title, "https://openagenda.com/fr/ville-de-lille/events/" + id, genres, false, LO, null);
    }

    private static Row row(String id, LocalDate night) {
        return row(id, night, "Event " + id, Set.of(HOUSE));
    }

    private List<OpenEventOccurrence> stored(String city) {
        em.flush();
        em.clear();
        return occurrences.findByCityKeyAndNightDateBetween(city, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1))
                .stream().sorted(Comparator.comparing(OpenEventOccurrence::getNightDate)
                        .thenComparing(OpenEventOccurrence::getSource)).toList();
    }

    private Map<String, GenreWeekCount> weekRows(String city, LocalDate week) {
        em.flush();
        em.clear();
        Map<String, GenreWeekCount> out = new LinkedHashMap<>();
        counts.findByCityKeyAndWeekStartIn(city, List.of(week)).forEach(c -> out.put(c.getGenreFamily(), c));
        return out;
    }

    private static Map<String, String> sources(String... idThenLicence) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < idThenLicence.length; i += 2) m.put(idThenLicence[i], idThenLicence[i + 1]);
        return m;
    }

    @Test
    void replaceFutureKeepsPastRows() {
        writer.insertBackfill("openagenda", "lille", FROM, List.of(row("1", LocalDate.of(2026, 9, 14))), T1);
        writer.replaceFuture("openagenda", "lille", FROM, List.of(row("2", LocalDate.of(2026, 10, 2))), T1);

        Written w = writer.replaceFuture("openagenda", "lille", FROM, List.of(row("3", LocalDate.of(2026, 10, 3))), T2);

        assertThat(w.inserted()).isEqualTo(1);
        assertThat(stored("lille")).extracting(OpenEventOccurrence::getSourceEventId, OpenEventOccurrence::getNightDate,
                        OpenEventOccurrence::getSyncedAt)
                .containsExactly(tuple("1", LocalDate.of(2026, 9, 14), T1), tuple("3", LocalDate.of(2026, 10, 3), T2));
    }

    @Test
    void replaceFutureOnlyTouchesItsSource() {
        writer.replaceFuture("openagenda", "paris", FROM, List.of(row("1", LocalDate.of(2026, 10, 2))), T1);
        writer.replaceFuture("quefaireaparis", "paris", FROM, List.of(row("q1", LocalDate.of(2026, 10, 2))), T1);
        writer.replaceFuture("quefaireaparis", "lille", FROM, List.of(row("q9", LocalDate.of(2026, 10, 2))), T1);

        writer.replaceFuture("quefaireaparis", "paris", FROM, List.of(), T2);

        assertThat(stored("paris")).extracting(OpenEventOccurrence::getSource, OpenEventOccurrence::getSourceEventId)
                .containsExactly(tuple("openagenda", "1"));
        assertThat(stored("lille")).extracting(OpenEventOccurrence::getSourceEventId).containsExactly("q9");
    }

    @Test
    void storedLastWeekRowSurvivesTonightReplaceAndStillCounts() {
        // Que Faire à Paris drops ended events, so the job replaces its rows from tonight, not from last Monday
        writer.replaceFuture("quefaireaparis", "paris", FROM,
                List.of(row("q1", LocalDate.of(2026, 9, 25), "Techno A", Set.of(HOUSE))), T1);

        writer.replaceFuture("quefaireaparis", "paris", LocalDate.of(2026, 10, 1), List.of(), T2);
        writer.recount("paris", List.of(FROM), sources("quefaireaparis", ODBL), T2);

        assertThat(stored("paris")).extracting(OpenEventOccurrence::getSourceEventId).containsExactly("q1");
        assertThat(weekRows("paris", FROM).get(HOUSE).getEventCount()).isEqualTo(1);
    }

    @Test
    void uncountedWeeksAreThoseWithoutAnyRow() {
        writer.recount("lille", List.of(FROM), sources("openagenda", LO), T1);
        writer.recount("paris", List.of(WEEK), sources("openagenda", LO), T1);

        assertThat(writer.uncountedWeeks("lille", List.of(FROM, WEEK))).containsExactly(WEEK);
        assertThat(writer.uncountedWeeks("lille", List.of())).isEmpty();
    }

    @Test
    void futureRowsAloneDoNotCountAsHistory() {
        // the job retries a backfill until past rows exist; this run's future rows must not look like history
        writer.replaceFuture("openagenda", "lille", FROM, List.of(row("1", LocalDate.of(2026, 10, 2))), T1);
        em.flush();

        assertThat(occurrences.existsBySourceAndCityKeyAndNightDateBefore("openagenda", "lille", FROM)).isFalse();
        writer.insertBackfill("openagenda", "lille", FROM, List.of(row("p", LocalDate.of(2026, 9, 14))), T1);
        em.flush();
        assertThat(occurrences.existsBySourceAndCityKeyAndNightDateBefore("openagenda", "lille", FROM)).isTrue();
    }

    @Test
    void storesEveryColumn() {
        Row r = new Row("77", LocalDate.of(2026, 10, 2), "Fête de la Musique — Techno!",
                "https://openagenda.com/fr/x/events/77", Set.of("jazz & acoustic", HOUSE), true, LO, "Office de tourisme");

        writer.replaceFuture("openagenda", "lille", FROM, List.of(r, r), T1);

        assertThat(stored("lille")).singleElement().satisfies(o -> {
            assertThat(o.getSource()).isEqualTo("openagenda");
            assertThat(o.getCityKey()).isEqualTo("lille");
            assertThat(o.getTitle()).isEqualTo("Fête de la Musique — Techno!");
            assertThat(o.getTitleKey()).isEqualTo("fete de la musique techno");
            assertThat(o.getUrl()).isEqualTo("https://openagenda.com/fr/x/events/77");
            // bank order, whatever the set order
            assertThat(o.getGenreKeys()).isEqualTo("[\"house & techno\",\"jazz & acoustic\"]");
            assertThat(o.isCommunity()).isTrue();
            assertThat(o.getLicence()).isEqualTo(LO);
            assertThat(o.getCredit()).isEqualTo("Office de tourisme");
        });
    }

    @Test
    void insertBackfillOnlyPastNightsAndOnlyOnce() {
        Written first = writer.insertBackfill("openagenda", "lille", FROM,
                List.of(row("1", LocalDate.of(2026, 9, 14)), row("2", FROM)), T1);
        Written second = writer.insertBackfill("openagenda", "lille", FROM, List.of(row("3", LocalDate.of(2026, 9, 7))), T2);

        assertThat(first.inserted()).isEqualTo(1);
        assertThat(second.inserted()).isZero();
        assertThat(stored("lille")).extracting(OpenEventOccurrence::getSourceEventId).containsExactly("1");
    }

    @Test
    void overlongUrlOrIdDropped() {
        Row longUrl = new Row("1", WEEK, "A", "https://x.org/" + "a".repeat(500), Set.of(HOUSE), false, LO, null);
        Row longId = new Row("9".repeat(65), WEEK, "B", "https://x.org/b", Set.of(HOUSE), false, LO, null);

        Written w = writer.replaceFuture("openagenda", "lille", FROM, List.of(longUrl, longId, row("ok", WEEK)), T1);

        assertThat(w).isEqualTo(new Written(1, 1, 1));
        assertThat(stored("lille")).extracting(OpenEventOccurrence::getSourceEventId).containsExactly("ok");
    }

    @Test
    void recountDedupsSameTitleSameNightAcrossSources() {
        LocalDate fri = LocalDate.of(2026, 10, 2);
        writer.replaceFuture("openagenda", "paris", FROM, List.of(row("1", fri, "Soirée Techno!", Set.of(HOUSE)),
                row("2", fri.plusDays(1), "Soirée Techno!", Set.of(HOUSE))), T1);
        writer.replaceFuture("quefaireaparis", "paris", FROM, List.of(row("q1", fri, "soiree  techno", Set.of(HOUSE))), T1);

        writer.recount("paris", List.of(WEEK), sources("openagenda", LO, "quefaireaparis", ODBL), T1);

        assertThat(weekRows("paris", WEEK).get(HOUSE).getEventCount()).isEqualTo(2);
    }

    @Test
    void recountWritesZeroRowsForEveryBucket() {
        writer.replaceFuture("openagenda", "lille", FROM, List.of(row("1", LocalDate.of(2026, 10, 4))), T1);

        int written = writer.recount("lille", List.of(FROM, WEEK), sources("openagenda", LO), T1);

        assertThat(written).isEqualTo(16);
        Map<String, GenreWeekCount> week = weekRows("lille", WEEK);
        assertThat(week.keySet()).containsExactlyInAnyOrderElementsOf(QuestionBank.GENRE_BUCKETS);
        week.forEach((bucket, c) -> {
            assertThat(c.getSubGenre()).isEmpty();
            assertThat(c.getEventCount()).as(bucket).isEqualTo(bucket.equals(HOUSE) ? 1 : 0);
            assertThat(c.getUpdatedAt()).isEqualTo(T1);
        });
        assertThat(weekRows("lille", FROM).values()).extracting(GenreWeekCount::getEventCount).containsOnly(0);
    }

    @Test
    void odblSourcesRecordedPerRow() {
        LocalDate fri = LocalDate.of(2026, 10, 2);
        writer.replaceFuture("openagenda", "paris", FROM, List.of(row("1", fri, "Techno A", Set.of(HOUSE))), T1);
        writer.replaceFuture("quefaireaparis", "paris", FROM, List.of(row("q1", fri, "Jazz B", Set.of("jazz & acoustic"))), T1);

        writer.recount("paris", List.of(WEEK), sources("openagenda", LO, "quefaireaparis", ODBL), T1);

        Map<String, GenreWeekCount> week = weekRows("paris", WEEK);
        assertThat(week.get(HOUSE).getSourcesJson()).isEqualTo("[{\"source\":\"openagenda\",\"licence\":\"Licence Ouverte 2.0\","
                + "\"events\":1},{\"source\":\"quefaireaparis\",\"licence\":\"ODbL 1.0\",\"events\":0}]");
        assertThat(week.get("pop").getSourcesJson()).isEqualTo("[{\"source\":\"openagenda\",\"licence\":\"Licence Ouverte 2.0\","
                + "\"events\":0},{\"source\":\"quefaireaparis\",\"licence\":\"ODbL 1.0\",\"events\":0}]");
    }

    @Test
    void recountOnlyCountsSourcesInTheSet() {
        LocalDate fri = LocalDate.of(2026, 10, 2);
        writer.replaceFuture("openagenda", "paris", FROM, List.of(row("1", fri, "Techno A", Set.of(HOUSE))), T1);
        writer.replaceFuture("quefaireaparis", "paris", FROM, List.of(row("q1", fri, "Techno B", Set.of(HOUSE))), T1);

        writer.recount("paris", List.of(WEEK), sources("openagenda", LO), T1);

        GenreWeekCount house = weekRows("paris", WEEK).get(HOUSE);
        assertThat(house.getEventCount()).isEqualTo(1);
        assertThat(house.getSourcesJson()).doesNotContain("quefaireaparis");
    }

    @Test
    void recountIsIdempotent() {
        writer.replaceFuture("openagenda", "lille", FROM, List.of(row("1", LocalDate.of(2026, 10, 4))), T1);
        writer.recount("lille", List.of(WEEK), sources("openagenda", LO), T1);
        Map<String, GenreWeekCount> before = weekRows("lille", WEEK);

        writer.recount("lille", List.of(WEEK), sources("openagenda", LO), T1);

        Map<String, GenreWeekCount> after = weekRows("lille", WEEK);
        assertThat(counts.count()).isEqualTo(8);
        after.forEach((bucket, c) -> {
            assertThat(c.getId()).isEqualTo(before.get(bucket).getId());
            assertThat(c.getEventCount()).isEqualTo(before.get(bucket).getEventCount());
            assertThat(c.getSourcesJson()).isEqualTo(before.get(bucket).getSourcesJson());
        });
    }

    @Test
    void recountRederivesAfterRowsChange() {
        writer.replaceFuture("openagenda", "lille", FROM, List.of(row("1", LocalDate.of(2026, 10, 4)),
                row("2", LocalDate.of(2026, 10, 3))), T1);
        writer.recount("lille", List.of(WEEK), sources("openagenda", LO), T1);

        writer.replaceFuture("openagenda", "lille", FROM, List.of(row("1", LocalDate.of(2026, 10, 4))), T2);
        writer.recount("lille", List.of(WEEK), sources("openagenda", LO), T2);

        GenreWeekCount house = weekRows("lille", WEEK).get(HOUSE);
        assertThat(house.getEventCount()).isEqualTo(1);
        // GenreWeekCount stamps an update with the wall clock
        assertThat(house.getUpdatedAt()).isAfter(T1);
    }

    @Test
    void pruneDeletesOlderThan200Days() {
        // 2026-10-01 minus 200 days is 2026-03-15
        writer.insertBackfill("openagenda", "lille", FROM, List.of(row("old", LocalDate.of(2026, 3, 14)),
                row("edge", LocalDate.of(2026, 3, 15))), T1);

        int deleted = writer.prune(LocalDate.of(2026, 10, 1));

        assertThat(deleted).isEqualTo(1);
        assertThat(stored("lille")).extracting(OpenEventOccurrence::getSourceEventId).containsExactly("edge");
    }

    @Test
    void titleCutAt255() {
        String title = "Techno ".repeat(50);

        writer.replaceFuture("openagenda", "lille", FROM, List.of(row("1", WEEK, title, Set.of(HOUSE))), T1);

        OpenEventOccurrence o = stored("lille").get(0);
        assertThat(o.getTitle()).hasSize(255).isEqualTo(title.substring(0, 255));
        assertThat(o.getTitleKey().length()).isLessThanOrEqualTo(255);
        assertThat(o.getTitleKey()).startsWith("techno techno");
    }
}
