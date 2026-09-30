package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.predictor.calendar.CalendarHit;
import com.imin.iminapi.predictor.calendar.CalendarRegions;
import com.imin.iminapi.predictor.calendar.ReferenceCalendarService;
import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static com.imin.iminapi.predictor.rules.RuleFixtures.BANK;
import static com.imin.iminapi.predictor.rules.RuleFixtures.in;
import static com.imin.iminapi.predictor.rules.RuleFixtures.q;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CalendarEvaluatorTest {

    private static final String HOLIDAY_URL = "https://calendrier.api.gouv.fr/jours-feries/metropole/2026.json";
    private static final CalendarRegions REGIONS = new CalendarRegions(OpenDataCities.load());

    private ReferenceCalendarService cal;
    private CalendarEvaluator evaluator;

    @BeforeEach
    void setUp() {
        cal = mock(ReferenceCalendarService.class);
        evaluator = new CalendarEvaluator(cal, REGIONS);
    }

    private void coversAll(String country) {
        when(cal.covers(eq(country), anyString(), any())).thenReturn(true);
    }

    /** FR data synced only for the given kinds in the given years. */
    private void coversOnly(Set<String> kinds, Set<Integer> years) {
        when(cal.covers(eq("FR"), anyString(), any())).thenAnswer(a ->
                kinds.contains(a.<String>getArgument(1)) && years.contains(a.<LocalDate>getArgument(2).getYear()));
    }

    private static void assertNoData(Finding f) {
        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_data");
    }

    private void hits(CalendarHit... hits) {
        when(cal.between(any(), any(), any())).thenReturn(List.of(hits));
    }

    private static CalendarHit holiday(String date, String name, String region) {
        return new CalendarHit(LocalDate.parse(date), null, "holiday", name, region, HOLIDAY_URL, false, CalendarHit.SYNCED);
    }

    private static CalendarHit hit(String kind, String from, String to, String name, String region) {
        return new CalendarHit(LocalDate.parse(from), to == null ? null : LocalDate.parse(to), kind, name, region,
                "https://example.org/" + kind, "hijri".equals(kind), CalendarHit.SYNCED);
    }

    private Finding eval(String id, DateCheckInput in, String date) {
        return evaluator.evaluate(q(id, SourceKind.STRUCTURED), in, LocalDate.parse(date));
    }

    private Finding paris(String id, String date) {
        return eval(id, in().build(), date);
    }

    // --- 4.1 ---

    @Test
    void holidayInWeekFound() {
        coversAll("FR");
        hits(holiday("2026-11-11", "Armistice", ""));

        Finding f = paris("4.1", "2026-11-07");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.url()).isEqualTo(HOLIDAY_URL);
        assertThat(f.facts()).containsEntry("date", "2026-11-11").containsEntry("name", "Armistice")
                .containsEntry("count", 1);
    }

    @Test
    void holidayOnDayStrength3() {
        coversAll("FR");
        hits(holiday("2026-11-11", "Armistice", ""));

        assertThat(paris("4.1", "2026-11-11").strength()).isEqualTo(3);
    }

    @Test
    void regionalHolidayIsNotANationalOne() {
        coversAll("FR");
        hits(holiday("2026-12-26", "Saint-Étienne", "FR-57"));

        assertThat(eval("4.1", in().city("Metz", "FR", "57000").build(), "2026-12-24").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void weekCrossingIntoAnUncoveredYearNotChecked() {
        when(cal.covers(eq("FR"), anyString(), any())).thenAnswer(a -> a.<LocalDate>getArgument(2).getYear() == 2027);
        hits();

        Finding holidays = paris("4.1", "2027-12-30");
        Finding ramadan = paris("5.2", "2027-12-30");
        Finding sameYear = paris("4.1", "2027-12-20");

        assertThat(holidays.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(holidays.facts()).containsEntry("reason", "no_data");
        assertThat(ramadan.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(sameYear.status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void noCoverageNotChecked() {
        hits();

        Finding f = paris("4.1", "2028-05-01");

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_data");
    }

    @Test
    void fallbackCoverageCountsAsChecked() {
        hits(new CalendarHit(LocalDate.parse("2026-10-01"), null, "holiday", "Defenders Day", "", null, false,
                CalendarHit.FALLBACK));
        DateCheckInput kyiv = in().city("Kyiv", "UA", null).build();

        Finding found = eval("4.1", kyiv, "2026-10-03");
        hits();
        Finding clear = eval("4.1", kyiv, "2026-11-20");

        assertThat(found.status()).isEqualTo(Status.FOUND);
        assertThat(found.url()).isNull();
        assertThat(clear.status()).isEqualTo(Status.CLEAR);
    }

    // --- 4.2 ---

    @Test
    void eveOfHolidayOpportunity() {
        coversAll("FR");
        hits(holiday("2026-11-11", "Armistice", ""), hit("pont", "2026-07-13", null, "pont:Fête nationale", ""));

        Finding eve = paris("4.2", "2026-11-10");
        Finding evePont = paris("4.2", "2026-07-12");

        assertThat(eve.status()).isEqualTo(Status.FOUND);
        assertThat(eve.kind()).isEqualTo(Kind.OPPORTUNITY);
        assertThat(eve.strength()).isEqualTo(3);
        assertThat(eve.facts()).containsEntry("date", "2026-11-11");
        assertThat(evePont.kind()).isEqualTo(Kind.OPPORTUNITY);
        assertThat(evePont.facts()).containsEntry("name", "pont:Fête nationale");
    }

    @Test
    void holidayBeforeWorkdayRisk() {
        coversAll("FR");
        hits(holiday("2026-11-11", "Armistice", ""));

        Finding f = paris("4.2", "2026-11-11");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(3);
    }

    @Test
    void neitherClear() {
        coversAll("FR");
        // Friday holiday: the next day is a Saturday, so no working day follows
        hits(holiday("2026-05-01", "Fête du Travail", ""), holiday("2026-11-11", "Armistice", ""));

        assertThat(paris("4.2", "2026-11-04").status()).isEqualTo(Status.CLEAR);
        Finding friday = paris("4.2", "2026-05-01");
        assertThat(friday.status()).isEqualTo(Status.CLEAR);
        assertThat(friday.kind()).isEqualTo(Kind.RISK);
    }

    @Test
    void eveCrossingIntoAnUncoveredYearNotChecked() {
        hits();

        coversOnly(Set.of("holiday"), Set.of(2028));
        assertNoData(paris("4.2", "2028-12-31"));
        coversOnly(Set.of("holiday"), Set.of(2028, 2029));
        assertThat(paris("4.2", "2028-12-31").status()).isEqualTo(Status.CLEAR);
    }

    // --- 4.3 ---

    @Test
    void pontFound() {
        coversAll("FR");
        hits(hit("pont", "2026-07-13", null, "pont:Fête nationale", ""));

        assertThat(paris("4.3", "2026-07-13").strength()).isEqualTo(3);
        Finding eve = paris("4.3", "2026-07-12");
        assertThat(eve.status()).isEqualTo(Status.FOUND);
        assertThat(eve.strength()).isEqualTo(2);
        assertThat(paris("4.3", "2026-07-10").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void noPontCoverageNotChecked() {
        when(cal.covers(eq("FR"), eq("holiday"), any())).thenReturn(true);
        hits(hit("pont", "2026-07-13", null, "pont:Fête nationale", ""));

        Finding f = paris("4.3", "2026-07-13");

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_data");
    }

    @Test
    void nationalPontOnARegionalHolidayDoesNotFireThere() {
        coversAll("FR");
        hits(hit("pont", "2025-12-26", null, "pont:Noël", ""), holiday("2025-12-26", "Saint-Étienne", "FR-57"));

        assertThat(eval("4.3", in().city("Metz", "FR", "57000").build(), "2025-12-26").status())
                .isEqualTo(Status.CLEAR);
        assertThat(paris("4.3", "2025-12-26").status()).isEqualTo(Status.FOUND);
    }

    @Test
    void regionalPlaceWithoutHolidayDataNotChecked() {
        coversOnly(Set.of("pont"), Set.of(2028));
        hits(hit("pont", "2028-07-14", null, "pont:Fête nationale", ""));
        DateCheckInput metz = in().city("Metz", "FR", "57000").build();

        assertNoData(eval("4.3", metz, "2028-07-14"));
        coversOnly(Set.of("pont", "holiday"), Set.of(2028));
        assertThat(eval("4.3", metz, "2028-07-14").status()).isEqualTo(Status.FOUND);
    }

    @Test
    void pontCrossingIntoAnUncoveredYearNotChecked() {
        hits();

        coversOnly(Set.of("pont", "holiday"), Set.of(2028));
        assertNoData(paris("4.3", "2028-12-31"));
        coversOnly(Set.of("pont", "holiday"), Set.of(2028, 2029));
        assertThat(paris("4.3", "2028-12-31").status()).isEqualTo(Status.CLEAR);
    }

    // --- 4.4 ---

    @Test
    void metzRegionalHolidayFound() {
        coversAll("FR");
        hits(holiday("2027-03-26", "Vendredi saint", "FR-57"));
        DateCheckInput metz = in().city("Metz", "FR", "57000").build();

        Finding on = eval("4.4", metz, "2027-03-26");
        Finding before = eval("4.4", metz, "2027-03-25");

        assertThat(on.status()).isEqualTo(Status.FOUND);
        assertThat(on.strength()).isEqualTo(3);
        assertThat(on.facts()).containsEntry("name", "Vendredi saint");
        assertThat(before.strength()).isEqualTo(2);
        assertThat(eval("4.4", metz, "2027-03-23").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void parisGetsClear() {
        coversAll("FR");
        hits(holiday("2027-03-26", "Vendredi saint", "FR-57"));

        assertThat(paris("4.4", "2027-03-26").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void regionalWindowCrossingBackIntoAnUncoveredYearNotChecked() {
        hits();
        DateCheckInput metz = in().city("Metz", "FR", "57000").build();

        coversOnly(Set.of("holiday"), Set.of(2029));
        assertNoData(eval("4.4", metz, "2029-01-01"));
        coversOnly(Set.of("holiday"), Set.of(2028, 2029));
        assertThat(eval("4.4", metz, "2029-01-01").status()).isEqualTo(Status.CLEAR);
    }

    // --- 4.7 ---

    @Test
    void dstBackOpportunity() {
        coversAll("FR");
        hits(hit("dst", "2026-10-24", null, "dst_back", ""));

        Finding f = paris("4.7", "2026-10-24");

        assertThat(f.kind()).isEqualTo(Kind.OPPORTUNITY);
        assertThat(f.strength()).isEqualTo(3);
        assertThat(paris("4.7", "2026-10-23").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void dstForwardRisk() {
        coversAll("FR");
        hits(hit("dst", "2027-03-27", null, "dst_forward", ""));

        Finding f = paris("4.7", "2027-03-27");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
    }

    // --- 5.2 ---

    @Test
    void ramadanFoundApproximateCappedAt2() {
        coversAll("FR");
        hits(hit("hijri", "2027-02-08", "2027-03-09", "ramadan", ""), hit("hijri", "2027-03-10", null, "eid_al_fitr", ""));

        Finding f = paris("5.2", "2027-02-20");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("name", "ramadan").containsEntry("approximate", true);
        hits(hit("hijri", "2027-03-10", null, "eid_al_fitr", ""));
        assertThat(paris("5.2", "2027-03-12").status()).isEqualTo(Status.CLEAR);
    }

    // --- 7.1 ---

    @Test
    void schoolHolidayFound() {
        coversAll("FR");
        hits(hit("school", "2026-10-17", "2026-11-01", "Vacances de la Toussaint", "FR-ZC"));

        Finding f = paris("7.1", "2026-10-24");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.strength()).isEqualTo(3);
        assertThat(f.facts()).containsEntry("date", "2026-10-17");
        assertThat(paris("7.1", "2026-11-06").strength()).isEqualTo(2);
    }

    @Test
    void otherZoneClear() {
        coversAll("FR");
        hits(hit("school", "2026-10-17", "2026-11-01", "Vacances de la Toussaint", "FR-ZA"));

        assertThat(paris("7.1", "2026-10-24").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void schoolWeekCrossingIntoAnUncoveredYearNotChecked() {
        hits();

        coversOnly(Set.of("school"), Set.of(2028));
        assertNoData(paris("7.1", "2028-12-28"));
        coversOnly(Set.of("school"), Set.of(2028, 2029));
        assertThat(paris("7.1", "2028-12-28").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void uaCityHasNoSchoolDataSoNotChecked() {
        coversAll("UA");
        hits();

        Finding f = eval("7.1", in().city("Kyiv", "UA", null).build(), "2026-10-24");

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_data");
    }

    @Test
    void corsicaNoZoneNotChecked() {
        coversAll("FR");
        hits(hit("school", "2026-10-17", "2026-11-01", "Vacances de la Toussaint", "FR-ZB"));

        assertThat(eval("7.1", in().city("Ajaccio", "FR", "20000").build(), "2026-10-24").status())
                .isEqualTo(Status.NOT_CHECKED);
    }

    @Test
    void m14bQuestionsNotCheckedWithReason() {
        for (String id : List.of("3.2", "4.5", "5.1", "10.3")) {
            Finding f = eval(id, in().city("Metz", "FR", "57000").build(), "2026-10-24");
            assertThat(f.status()).as(id).isEqualTo(Status.NOT_CHECKED);
            assertThat(f.facts()).as(id).containsEntry("reason", "no_source");
        }
        verifyNoInteractions(cal);
    }

    @Test
    void sameFactHitsAllFiveCandidateDatesOnce() {
        coversAll("FR");
        CalendarHit za = hit("school", "2026-12-19", "2027-01-03", "Vacances de Noël", "FR-ZA");
        CalendarHit zb = hit("school", "2026-12-19", "2027-01-03", "Vacances de Noël", "FR-ZB");
        CalendarHit zc = hit("school", "2026-12-19", "2027-01-03", "Vacances de Noël", "FR-ZC");
        CalendarHit zcAgain = hit("school", "2026-12-19", "2027-01-03", "Vacances de Noël", "FR-ZC");
        hits(za, zb, zc, zcAgain);
        List<Question> structured = BANK.questionsFor("FR").stream()
                .filter(q -> q.source() == SourceKind.STRUCTURED && q.cities().isEmpty()).toList();
        List<String> candidates = List.of("2026-12-19", "2026-12-24", "2026-12-26", "2026-12-31", "2027-01-02");

        for (String d : candidates) {
            List<Finding> out = evaluator.evaluateAll(structured, in().build(), LocalDate.parse(d));
            List<Finding> school = out.stream().filter(f -> f.questionId().equals("7.1")).toList();
            assertThat(out).hasSize(structured.size());
            assertThat(school).as(d).hasSize(1);
            assertThat(school.get(0).status()).as(d).isEqualTo(Status.FOUND);
            assertThat(school.get(0).facts()).as(d).containsEntry("name", "Vacances de Noël").containsEntry("count", 1);
        }
        verify(cal, times(candidates.size())).between(any(), any(), any());
    }
}
