package com.imin.iminapi.predictor.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FrenchSchoolCalendarSyncTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final String URL = FrenchSchoolCalendarSync.EXPORT_URL
            + "?where=annee_scolaire+in+%28%222026-2027%22%2C%222027-2028%22%29";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FrenchSchoolCalendarSync sync = new FrenchSchoolCalendarSync(builder.build(), CalendarFixtures.props(1));

    private List<CalendarRow> fetchFixture() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess(CalendarFixtures.text("calendrier-scolaire.json"), MediaType.APPLICATION_JSON));
        List<CalendarSource.Batch> batches = sync.fetch(TODAY);
        server.verify();
        assertThat(batches).hasSize(1);
        CalendarSource.Batch b = batches.get(0);
        assertThat(b.sourceUrl()).isEqualTo(FrenchSchoolCalendarSync.SOURCE_URL);
        assertThat(b.kinds()).containsExactly("school");
        assertThat(b.from()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(b.to()).isEqualTo(LocalDate.of(2028, 7, 31));
        return b.rows();
    }

    @Test
    void parisSchoolHolidayUtcRowMapsTo17Oct() {
        // start 2026-10-16T22:00Z; end 2026-11-01T23:00Z = midnight Monday 2 Nov, the day school restarts
        CalendarRow toussaint = fetchFixture().stream()
                .filter(r -> r.region().equals("FR-ZC") && r.name().equals("Vacances de la Toussaint"))
                .findFirst().orElseThrow();

        assertThat(toussaint).isEqualTo(new CalendarRow("FR", "FR-ZC", LocalDate.of(2026, 10, 17),
                LocalDate.of(2026, 11, 1), "school", "Vacances de la Toussaint", FrenchSchoolCalendarSync.SOURCE_URL));
    }

    @Test
    void singleDayRowWithEndEqualToStartHasNoEndDate() {
        // 2027 pont: start and end both 2027-05-06T22:00Z, i.e. Friday 7 May only
        List<CalendarRow> ponts = fetchFixture().stream().filter(r -> r.name().equals("Pont de l'Ascension")).toList();

        assertThat(ponts).extracting(CalendarRow::date, CalendarRow::endDate).containsExactly(
                tuple(LocalDate.of(2027, 5, 7), null),
                tuple(LocalDate.of(2028, 5, 25), LocalDate.of(2028, 5, 28)));
    }

    @Test
    void teacherRowsSkipped() {
        // teachers' row comes first with the same key and ends a day earlier; pupils' rentrée is Thu 2 Sep
        List<CalendarRow> summer = fetchFixture().stream().filter(r -> r.name().equals("Vacances d'Été")).toList();

        assertThat(summer).extracting(CalendarRow::endDate).containsExactly(LocalDate.of(2027, 9, 1));
    }

    @Test
    void nonMidnightEndDateIsKeptAsTheLastDay() throws Exception {
        // synthetic row: an end at local noon is itself a day off, not the day school restarts
        JsonNode row = new ObjectMapper().readTree("""
                {"description": "Pont", "population": "-", "zones": "Zone A",
                 "start_date": "2027-05-06T22:00:00+00:00", "end_date": "2027-05-09T10:00:00+00:00"}""");

        assertThat(FrenchSchoolCalendarSync.toRow(row)).isEqualTo(new CalendarRow("FR", "FR-ZA",
                LocalDate.of(2027, 5, 7), LocalDate.of(2027, 5, 9), "school", "Pont", FrenchSchoolCalendarSync.SOURCE_URL));
    }

    @Test
    void nonMetropolitanZoneSkipped() {
        assertThat(fetchFixture()).extracting(CalendarRow::region).containsOnly("FR-ZA", "FR-ZC");
    }

    @Test
    void academiesOfOneZoneDeduplicated() {
        // Paris and Créteil both give the zone C Toussaint; Lyon gives zone A's
        List<CalendarRow> toussaint = fetchFixture().stream()
                .filter(r -> r.name().equals("Vacances de la Toussaint")).toList();

        assertThat(toussaint).extracting(CalendarRow::region).containsExactly("FR-ZC", "FR-ZA");
    }

    @Test
    void upstreamErrorReturnsNoBatch() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(sync.fetch(TODAY)).isEmpty();
    }

    @Test
    void schoolYearTurnsInAugust() {
        assertThat(FrenchSchoolCalendarSync.firstSchoolYear(LocalDate.of(2027, 7, 31))).isEqualTo(2026);
        assertThat(FrenchSchoolCalendarSync.firstSchoolYear(LocalDate.of(2027, 8, 1))).isEqualTo(2027);
    }
}
