package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.predictor.calendar.CalendarHit;
import com.imin.iminapi.predictor.calendar.CalendarPlace;
import com.imin.iminapi.predictor.calendar.CalendarRegions;
import com.imin.iminapi.predictor.calendar.FootballFixturesSync;
import com.imin.iminapi.predictor.calendar.ReferenceCalendarService;
import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.sources.SourceGates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.imin.iminapi.predictor.rules.RuleFixtures.BANK;
import static com.imin.iminapi.predictor.rules.RuleFixtures.in;
import static com.imin.iminapi.predictor.rules.RuleFixtures.q;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    private SourceGates gates;
    private CalendarEvaluator evaluator;

    @BeforeEach
    void setUp() {
        cal = mock(ReferenceCalendarService.class);
        gates = mock(SourceGates.class);
        when(gates.isOn("football")).thenReturn(true);
        evaluator = new CalendarEvaluator(cal, REGIONS, gates);
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
        assertThat(CalendarEvaluator.NO_SOURCE_YET).containsExactlyInAnyOrder("5.1", "10.3");
        for (String id : List.of("5.1", "10.3")) {
            Finding f = eval(id, in().city("Metz", "FR", "57000").build(), "2026-10-24");
            assertThat(f.status()).as(id).isEqualTo(Status.NOT_CHECKED);
            assertThat(f.facts()).as(id).containsEntry("reason", "no_source");
        }
        verifyNoInteractions(cal);
    }

    // --- 3.2 ---

    private static final String FL1_URL = "https://api.football-data.org/v4/competitions/FL1/matches";
    private static final String CL_URL = "https://api.football-data.org/v4/competitions/CL/matches";
    /** football-data team ids from the recorded answers: PSG 524, Marseille 516, Lille 521, Lens 546, Barça 81. */
    private static final String PSG_AWAY_21 = "FL1|21:00|516|524|Marseille – PSG";

    private static CalendarHit fixture(String date, String name) {
        return new CalendarHit(LocalDate.parse(date), null, "fixture", name, "",
                name.startsWith("CL|") ? CL_URL : FL1_URL, false, CalendarHit.SYNCED);
    }

    /** Synced two days before the fixture input's today (2026-09-30), so never stale. */
    private void fixtures(String latest, CalendarHit... hits) {
        when(cal.lastSynced("FR", "fixture")).thenReturn(Optional.of(java.time.Instant.parse("2026-09-28T02:30:00Z")));
        when(cal.latest("FR", "fixture")).thenReturn(Optional.ofNullable(latest).map(LocalDate::parse));
        when(cal.between(any(), any(), any())).thenReturn(List.of(hits));
    }

    private static CalendarHit matchday(String from, String to, String name) {
        return new CalendarHit(LocalDate.parse(from), LocalDate.parse(to), "fixture", name, "", FL1_URL, false,
                CalendarHit.SYNCED);
    }

    /** 3.2 for a city and the event's start/end hours (the fixture input has 23 → 5). */
    private Finding football(String city, Integer startHour, Integer endHour, String date) {
        DateCheckInput b = in().city(city, "FR", null).build();
        DateCheckInput x = new DateCheckInput(b.city(), b.country(), b.postalCode(), b.venueLat(), b.venueLng(),
                b.genreFamily(), b.subGenre(), b.capacity(), b.priceMinor(), b.format(), startHour, endHour,
                b.lineup(), b.knownEvents(), b.orgId(), b.today(), b.audienceAge(), b.communities(),
                b.buyingLeadDays(), b.excludeEventId());
        return evaluator.evaluate(q("3.2", SourceKind.STRUCTURED), x, LocalDate.parse(date));
    }

    private static String actionKey(Finding f, String date) {
        return ActionPicker.pick(List.of(f), BANK, LocalDate.parse(date), RuleFixtures.TODAY).get(0).key();
    }

    @Test
    void nonFrFootballIsNoSource() {
        Finding f = eval("3.2", in().city("Amsterdam", "NL", null).build(), "2026-10-24");

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_source");
        verifyNoInteractions(cal);
    }

    @Test
    void footballGateOffIsSourceOff() {
        when(gates.isOn("football")).thenReturn(false);

        Finding f = paris("3.2", "2026-10-24");

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "source_off");
        verifyNoInteractions(cal);
    }

    @Test
    void dateBeyondLatestFixtureIsNoData() {
        fixtures("2027-05-29", fixture("2027-06-05", PSG_AWAY_21));
        assertNoData(football("Paris", 20, null, "2027-06-05"));

        fixtures(null);
        assertNoData(football("Paris", 20, null, "2026-10-24"));

        when(cal.lastSynced("FR", "fixture")).thenReturn(Optional.empty());
        assertNoData(football("Paris", 20, null, "2026-10-24"));
    }

    @Test
    void fixturesNotSyncedFor14DaysAreStale() {
        fixtures("2027-05-29", fixture("2026-10-24", PSG_AWAY_21));
        when(cal.lastSynced("FR", "fixture")).thenReturn(Optional.of(java.time.Instant.parse("2026-09-15T23:00:00Z")));

        Finding stale = football("Paris", 20, null, "2026-10-24");
        when(cal.lastSynced("FR", "fixture")).thenReturn(Optional.of(java.time.Instant.parse("2026-09-16T00:00:00Z")));
        Finding fresh = football("Paris", 20, null, "2026-10-24");

        assertThat(stale.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(stale.facts()).containsEntry("reason", "stale");
        assertThat(fresh.status()).isEqualTo(Status.FOUND);
    }

    @Test
    void otherClubsLeagueMatchIsClear() {
        fixtures("2027-05-29", fixture("2026-10-30", "FL1|21:05|521|546|Lille – RC Lens"));

        assertThat(football("Paris", 20, null, "2026-10-30").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void ownClubMatchOverlappingStartIsRisk2() {
        fixtures("2027-05-29", fixture("2026-10-24", PSG_AWAY_21));

        Finding f = football("Paris", 20, null, "2026-10-24");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("date", "2026-10-24",
                "name", "Marseille – PSG", "competition", "FL1", "kickoff", "21:00", "count", 1));
        assertThat(f.url()).isEqualTo(FootballFixturesSync.PUBLIC_URL).isEqualTo("https://www.football-data.org/");
        assertThat(actionKey(f, "2026-10-24")).isEqualTo("predictor.a.match_start_time");
    }

    @Test
    void matchEndingBeforeDoorsIsOpportunity() {
        fixtures("2027-05-29", fixture("2026-10-24", PSG_AWAY_21));

        Finding f = football("Paris", 23, 5, "2026-10-24");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.OPPORTUNITY);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("kickoff", "21:00").containsEntry("count", 1);
        assertThat(actionKey(f, "2026-10-24")).isEqualTo("predictor.a.match_screening");
    }

    @Test
    void afternoonMatchIsClear() {
        fixtures("2027-05-29", fixture("2026-10-24", "FL1|15:00|516|524|Marseille – PSG"));

        assertThat(football("Paris", 23, 5, "2026-10-24").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void matchAfterEventEndIsClear() {
        fixtures("2027-05-29", fixture("2026-10-24", PSG_AWAY_21));

        assertThat(football("Paris", 14, 20, "2026-10-24").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void tbcMatchdayIsRiskOnFridaySaturdayAndSunday() {
        fixtures("2027-05-29", matchday("2026-12-04", "2026-12-06", "FL1|TBC|511|524|Toulouse – PSG"));

        for (String day : List.of("2026-12-04", "2026-12-05", "2026-12-06")) {
            Finding f = football("Paris", 23, 5, day);
            assertThat(f.kind()).as(day).isEqualTo(Kind.RISK);
            assertThat(f.strength()).as(day).isEqualTo(2);
            // the matchday range, never a single day the match is not known to be on
            assertThat(f.facts()).as(day).containsEntry("date", "2026-12-04").containsEntry("endDate", "2026-12-06")
                    .containsEntry("name", "Toulouse – PSG").doesNotContainKey("kickoff");
        }
    }

    @Test
    void tbcMatchdayIsClearOnThursdayAndMonday() {
        fixtures("2027-05-29", matchday("2026-12-04", "2026-12-06", "FL1|TBC|511|524|Toulouse – PSG"));

        assertThat(football("Paris", 23, 5, "2026-12-03").status()).isEqualTo(Status.CLEAR);
        assertThat(football("Paris", 23, 5, "2026-12-07").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void timedMatchCountsOnlyOnItsOwnNight() {
        fixtures("2027-05-29", fixture("2026-12-05", PSG_AWAY_21));

        assertThat(football("Paris", 20, null, "2026-12-04").status()).isEqualTo(Status.CLEAR);
        Finding f = football("Paris", 20, null, "2026-12-05");
        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.facts()).containsEntry("date", "2026-12-05").doesNotContainKey("endDate");
    }

    @Test
    void endBeforeStartRunsToNightEnd() {
        // 22 → 21 reads as the event ending before it starts; the event is taken to run to 06:00
        fixtures("2027-05-29", fixture("2026-10-24", "FL1|23:00|516|524|Marseille – PSG"));

        assertThat(football("Paris", 22, 21, "2026-10-24").kind()).isEqualTo(Kind.RISK);
    }

    @Test
    void endAfterRolloverIsTruncatedToSixAm() {
        // start 03, end 07: 07:00 is after the 06:00 rollover, so the event runs to 06:00 and a 05:00 match overlaps
        fixtures("2027-05-29", fixture("2026-10-24", "FL1|05:00|516|524|Marseille – PSG"));

        assertThat(football("Paris", 3, 7, "2026-10-24").kind()).isEqualTo(Kind.RISK);
    }

    @Test
    void unparseableFixtureNameSkipped() {
        fixtures("2027-05-29", fixture("2026-10-24", "garbage"), fixture("2026-10-24", "FL1|xx:yy|516|524|Marseille – PSG"));
        assertThat(football("Paris", 20, null, "2026-10-24").status()).isEqualTo(Status.CLEAR);

        fixtures("2027-05-29", fixture("2026-10-24", "garbage"), fixture("2026-10-24", PSG_AWAY_21));
        assertThat(football("Paris", 20, null, "2026-10-24").facts()).containsEntry("count", 1);
    }

    @Test
    void nullStartHourIsRisk() {
        fixtures("2027-05-29", fixture("2026-10-24", "FL1|15:00|516|524|Marseille – PSG"));

        Finding f = football("Paris", null, null, "2026-10-24");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
    }

    @Test
    void startAfterMidnightCountsAsNextDay() {
        fixtures("2027-05-29", fixture("2026-10-24", PSG_AWAY_21));

        Finding f = football("Paris", 0, 5, "2026-10-24");

        assertThat(f.kind()).isEqualTo(Kind.OPPORTUNITY);
    }

    @Test
    void lateKickoffAfterMidnightOverlapsANightEvent() {
        fixtures("2027-05-29", fixture("2026-10-24", "FL1|00:30|516|524|Marseille – PSG"));

        assertThat(football("Paris", 23, 5, "2026-10-24").kind()).isEqualTo(Kind.RISK);
    }

    @Test
    void clMatchIsStrength3InCityWithoutClub() {
        fixtures("2027-05-29", fixture("2026-10-20", "CL|21:00|524|81|PSG – Barça"));

        Finding f = football("Metz", 20, null, "2026-10-20");

        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(3);
        assertThat(f.facts()).containsEntry("competition", "CL").containsEntry("name", "PSG – Barça");
    }

    @Test
    void riskWinsWhenBothPresent() {
        fixtures("2027-05-29",
                fixture("2026-10-24", "CL|19:00|524|81|PSG – Barça"),
                fixture("2026-10-24", "FL1|22:00|521|524|Lille – PSG"));

        Finding f = football("Paris", 22, 5, "2026-10-24");

        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("competition", "FL1").containsEntry("count", 1);
    }

    @Test
    void clChosenFirstAndCountsEveryFixtureOfTheKind() {
        fixtures("2027-05-29",
                fixture("2026-10-24", PSG_AWAY_21),
                fixture("2026-10-24", "CL|21:00|546|81|RC Lens – Barça"));

        Finding f = football("Paris", 20, null, "2026-10-24");

        assertThat(f.strength()).isEqualTo(3);
        assertThat(f.facts()).containsEntry("competition", "CL").containsEntry("name", "RC Lens – Barça")
                .containsEntry("count", 2);
    }

    @Test
    void noFixtureOnDateIsClear() {
        fixtures("2027-05-29", fixture("2026-10-25", PSG_AWAY_21), hit("holiday", "2026-10-24", null, "x", ""));

        assertThat(football("Paris", 20, null, "2026-10-24").status()).isEqualTo(Status.CLEAR);
    }

    // --- 4.5 ---

    private static final String LU_URL = "https://openholidaysapi.org/PublicHolidays?countryIsoCode=LU&languageIsoCode=EN"
            + "&validFrom=2026-01-01&validTo=2026-12-31";
    private static final String DE_URL = "https://openholidaysapi.org/PublicHolidays?countryIsoCode=DE&languageIsoCode=EN"
            + "&validFrom=2026-01-01&validTo=2026-12-31";

    private void neighbourHits(String country, CalendarHit... hits) {
        when(cal.between(any(), any(), argThat(p -> p != null && country.equals(p.country())))).thenReturn(List.of(hits));
    }

    private static CalendarHit neighbourHoliday(String date, String name, String region, String url) {
        return new CalendarHit(LocalDate.parse(date), null, "holiday", name, region, url, false, CalendarHit.SYNCED);
    }

    private Finding metz(String date) {
        return eval("4.5", in().city("Metz", "FR", "57000").build(), date);
    }

    @Test
    void metzLuxHolidayNextDayIsOpportunity3() {
        coversAll("LU");
        coversAll("DE");
        neighbourHits("LU", neighbourHoliday("2026-11-01", "All Saints", "", LU_URL));

        Finding f = metz("2026-10-31");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.OPPORTUNITY);
        assertThat(f.strength()).isEqualTo(3);
        assertThat(f.url()).isEqualTo(LU_URL);
        assertThat(f.facts()).containsEntry("date", "2026-11-01").containsEntry("name", "All Saints")
                .containsEntry("country", "LU").containsEntry("count", 1).doesNotContainKey("endDate");
        LocalDate d = LocalDate.of(2026, 10, 31);
        verify(cal).between(d, d.plusDays(1), new CalendarPlace("LU", null, null));
        verify(cal).between(d, d.plusDays(1), new CalendarPlace("DE", "DE-SL", null));
        verify(cal).between(d, d.plusDays(1), new CalendarPlace("DE", "DE-RP", null));
    }

    @Test
    void neighbourHolidayOnDayIsStrength2() {
        coversAll("LU");
        coversAll("DE");
        neighbourHits("DE", neighbourHoliday("2026-11-01", "All Saints' Day", "DE-SL", DE_URL),
                neighbourHoliday("2026-11-01", "All Saints' Day", "DE-SL", DE_URL));

        Finding f = metz("2026-11-01");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.OPPORTUNITY);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("date", "2026-11-01").containsEntry("country", "DE")
                .containsEntry("count", 1);
    }

    @Test
    void newYearsEveBeforeNeighbourNewYearIsStrength3() {
        coversAll("LU");
        coversAll("DE");
        String lu2027 = LU_URL.replace("2026", "2027");
        neighbourHits("LU", neighbourHoliday("2027-01-01", "New Year", "", lu2027));

        Finding f = metz("2026-12-31");

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.strength()).isEqualTo(3);
        assertThat(f.url()).isEqualTo(lu2027);
        assertThat(f.facts()).containsEntry("date", "2027-01-01").containsEntry("country", "LU");
        verify(cal).covers("LU", "holiday", LocalDate.of(2026, 12, 31));
        verify(cal).covers("LU", "holiday", LocalDate.of(2027, 1, 1));
        verify(cal).between(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1), new CalendarPlace("LU", null, null));
    }

    @Test
    void holidayOnBothDaysTakesTheNextDayAtStrength3() {
        coversAll("LU");
        coversAll("DE");
        neighbourHits("LU", neighbourHoliday("2026-12-25", "Christmas Day", "", LU_URL),
                neighbourHoliday("2026-12-26", "Boxing Day", "", LU_URL));

        Finding f = metz("2026-12-25");

        assertThat(f.strength()).isEqualTo(3);
        assertThat(f.facts()).containsEntry("date", "2026-12-26").containsEntry("name", "Boxing Day")
                .containsEntry("count", 1);
    }

    @Test
    void mulhouseReadsGermanAndSwissRegionsAndNamesTheCountry() {
        coversAll("DE");
        coversAll("CH");
        String chUrl = "https://openholidaysapi.org/PublicHolidays?countryIsoCode=CH&languageIsoCode=EN"
                + "&validFrom=2026-01-01&validTo=2026-12-31";
        neighbourHits("DE", neighbourHoliday("2026-04-30", "German day", "DE-BW", DE_URL));
        neighbourHits("CH", neighbourHoliday("2026-05-01", "Labour Day", "CH-BS", chUrl));
        DateCheckInput mulhouse = in().city("Mulhouse", "FR", "68100").build();
        LocalDate d = LocalDate.of(2026, 4, 30);

        Finding f = eval("4.5", mulhouse, "2026-04-30");

        assertThat(f.strength()).isEqualTo(3);
        assertThat(f.url()).isEqualTo(chUrl);
        assertThat(f.facts()).containsEntry("date", "2026-05-01").containsEntry("country", "CH");
        verify(cal).between(d, d.plusDays(1), new CalendarPlace("DE", "DE-BW", null));
        verify(cal).between(d, d.plusDays(1), new CalendarPlace("CH", "CH-BS", null));
        verify(cal).between(d, d.plusDays(1), new CalendarPlace("CH", "CH-BL", null));

        // only the German day left: strength 2, country DE
        neighbourHits("CH");
        Finding de = eval("4.5", mulhouse, "2026-04-30");
        assertThat(de.strength()).isEqualTo(2);
        assertThat(de.facts()).containsEntry("date", "2026-04-30").containsEntry("country", "DE");
    }

    @Test
    void neighbourWithoutHolidayIsClear() {
        coversAll("LU");
        coversAll("DE");
        // other kinds the neighbour's place returns (DST, Hijri) are not days off
        neighbourHits("DE", new CalendarHit(LocalDate.parse("2026-10-24"), null, "dst", "dst_back", "", "u", false,
                CalendarHit.SYNCED));

        assertThat(metz("2026-10-24").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void neighbourUncoveredIsNoData() {
        coversAll("LU");
        when(cal.covers(eq("DE"), anyString(), any())).thenReturn(false);

        assertNoData(metz("2026-10-31"));

        // d+1 in a year the neighbour has no rows for
        when(cal.covers(eq("DE"), anyString(), any())).thenAnswer(a -> a.<LocalDate>getArgument(2).getYear() == 2026);
        assertNoData(metz("2026-12-31"));
        assertThat(metz("2026-12-30").status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void cityWithoutNeighboursIsNoSource() {
        Finding f = eval("4.5", in().build(), "2026-10-31");

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_source");
        verifyNoInteractions(cal);
    }

    // --- endDate ---

    @Test
    void schoolFindingCarriesEndDate() {
        coversAll("FR");
        hits(hit("school", "2026-10-17", "2026-11-01", "Vacances de la Toussaint", "FR-ZC"));

        assertThat(paris("7.1", "2026-10-24").facts()).containsEntry("date", "2026-10-17")
                .containsEntry("endDate", "2026-11-01");
    }

    @Test
    void ramadanFindingCarriesEndDate() {
        coversAll("FR");
        hits(hit("hijri", "2027-02-08", "2027-03-09", "ramadan", ""));

        assertThat(paris("5.2", "2027-02-20").facts()).containsEntry("endDate", "2027-03-09");
    }

    @Test
    void singleDayHolidayHasNoEndDate() {
        coversAll("FR");
        hits(holiday("2026-11-11", "Armistice", ""));

        assertThat(paris("4.1", "2026-11-11").facts()).containsEntry("date", "2026-11-11").doesNotContainKey("endDate");
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
                .filter(q -> q.source() == SourceKind.STRUCTURED && q.cities().isEmpty())
                .filter(q -> evaluator.questionIds().contains(q.id())).toList();
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
