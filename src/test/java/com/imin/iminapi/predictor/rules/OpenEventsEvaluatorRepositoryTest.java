package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.repository.GenreWeekCountRepository;
import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities;
import com.imin.iminapi.predictor.sources.openevents.OpenEventSource;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter.Row;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The evaluator over rows the writer stored, through the real derived queries on H2. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional
class OpenEventsEvaluatorRepositoryTest {

    private static final String HOUSE = "house & techno";
    private static final String LO = "Licence Ouverte 2.0";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final LocalDate THU = LocalDate.of(2026, 10, 8);
    private static final Instant SYNCED = Instant.parse("2026-09-28T03:45:00Z");

    @Autowired OpenEventsWriter writer;
    @Autowired GenreWeekCountRepository counts;
    @Autowired OpenEventOccurrenceRepository occurrences;
    @Autowired QuestionBank bank;
    @Autowired OpenEventCities cities;
    @Autowired List<OpenEventSource> sources;
    @Autowired DataSourceCatalog catalog;
    @Autowired EntityManager em;

    private static Row row(String id, LocalDate night, String title, Set<String> genres, boolean community) {
        return new Row(id, night, title, "https://openagenda.com/fr/ville-de-lille/events/" + id, genres, community, LO, null);
    }

    @Test
    void answersFromStoredRows() {
        List<Row> rows = new ArrayList<>();
        // one house night in each of the 12 past weeks, then a busy candidate week with a weekly series
        for (int w = 12; w >= 1; w--) {
            rows.add(row("past" + w, LocalDate.of(2026, 9, 29).minusWeeks(w), "One-off " + w, Set.of(HOUSE), false));
        }
        for (LocalDate n : List.of(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 24))) {
            rows.add(row("weekly-" + n, n, "Techno Thursday", Set.of(HOUSE), false));
        }
        for (int i = 0; i < 4; i++) rows.add(row("busy" + i, THU, "Rave " + i, Set.of(HOUSE), false));
        rows.add(row("carnaval", THU.plusDays(1), "Carnaval de Lille", Set.of(), true));
        writer.replaceFuture("openagenda", "lille", LocalDate.of(2026, 3, 1), rows, SYNCED);
        List<LocalDate> weeks = new ArrayList<>();
        for (LocalDate m = LocalDate.of(2026, 7, 6); !m.isAfter(LocalDate.of(2026, 10, 5)); m = m.plusWeeks(1)) weeks.add(m);
        writer.recount("lille", weeks, Map.of("openagenda", LO), SYNCED);
        em.flush();
        em.clear();

        SourceGates gates = mock(SourceGates.class);
        when(gates.isOn(anyString())).thenReturn(true);
        OpenEventsEvaluator evaluator = new OpenEventsEvaluator(bank, cities, sources, counts, occurrences, gates, catalog);
        DateCheckInput in = new DateCheckInput("Lille", "FR", "59000", null, null, HOUSE, null, 300, 2000L, "club",
                23, 5, List.of(), null, UUID.randomUUID(), TODAY, null, null, null, null);
        List<Question> qs = bank.questions().stream()
                .filter(q -> q.source() == SourceKind.STRUCTURED && evaluator.questionIds().contains(q.id())).toList();

        List<Finding> out = evaluator.evaluateAll(qs, in, THU);

        assertThat(out).extracting(Finding::questionId).containsExactly("2.6", "5.3", "2.3");
        assertThat(out).allSatisfy(f -> assertThat(f.status()).isEqualTo(Finding.Status.FOUND));
        // nine past weeks of 1 and three of 2 (the weekly series) make a norm of 1; four raves in the week of 10-05
        assertThat(out.get(0).facts()).containsEntry("weekStart", "2026-10-05").containsEntry("count", 4);
        assertThat(out.get(1).facts()).containsEntry("name", "Carnaval de Lille").containsEntry("date", "2026-10-09");
        assertThat(out.get(2).facts()).containsEntry("name", "Techno Thursday").containsEntry("pattern", "weekly");
    }
}
