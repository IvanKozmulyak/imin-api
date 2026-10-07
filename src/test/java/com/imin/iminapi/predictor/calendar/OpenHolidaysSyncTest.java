package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@IminIntegrationTest
@Transactional
class OpenHolidaysSyncTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @Autowired ReferenceCalendarEntryRepository repository;
    @Autowired ReferenceCalendarWriter writer;
    @Autowired ReferenceCalendarService service;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private List<CalendarSource.Batch> fetch(String country, String body) {
        server.reset();
        server.expect(requestTo(OpenHolidaysSync.url(country, 2026)))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        List<CalendarSource.Batch> batches =
                new OpenHolidaysSync(builder.build(), CalendarFixtures.props(0), List.of(country)).fetch(TODAY);
        server.verify();
        return batches;
    }

    private List<CalendarRow> rows(String country, String body) {
        return fetch(country, body).stream().flatMap(b -> b.rows().stream()).toList();
    }

    @Test
    void urlIsOneYearOfOneCountryInEnglish() {
        assertThat(OpenHolidaysSync.url("LU", 2026)).isEqualTo("https://openholidaysapi.org/PublicHolidays?"
                + "countryIsoCode=LU&languageIsoCode=EN&validFrom=2026-01-01&validTo=2026-12-31");
        assertThat(OpenHolidaysSync.url("LU", 2026)).startsWith(new OpenHolidaysSync(null, null).scopePrefix());
        assertThat(OpenHolidaysSync.COUNTRIES).containsExactly("LU", "DE", "BE", "CH", "ES", "PT", "NL");
    }

    @Test
    void oneCallPerCountryAndYear() {
        for (String c : List.of("LU", "BE")) {
            for (int y = 2026; y <= 2027; y++) {
                server.expect(requestTo(OpenHolidaysSync.url(c, y))).andRespond(withSuccess(
                        "[{\"startDate\":\"" + y + "-01-01\",\"endDate\":\"" + y + "-01-01\",\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"nationwide\":true,"
                                + "\"name\":[{\"language\":\"EN\",\"text\":\"New Year\"}]}]", MediaType.APPLICATION_JSON));
            }
        }

        List<CalendarSource.Batch> batches =
                new OpenHolidaysSync(builder.build(), CalendarFixtures.props(1), List.of("LU", "BE")).fetch(TODAY);

        server.verify();
        assertThat(batches).extracting(CalendarSource.Batch::sourceUrl, CalendarSource.Batch::from, CalendarSource.Batch::to)
                .containsExactly(
                        tuple(OpenHolidaysSync.url("LU", 2026), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
                        tuple(OpenHolidaysSync.url("LU", 2027), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)),
                        tuple(OpenHolidaysSync.url("BE", 2026), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
                        tuple(OpenHolidaysSync.url("BE", 2027), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)));
        assertThat(batches).allSatisfy(b -> assertThat(b.kinds()).containsExactly("holiday"));
    }

    @Test
    void luToussaint2026FromOpenHolidays() {
        fetch("LU", CalendarFixtures.text("openholidays-LU-2026.json")).forEach(writer::replace);

        List<CalendarHit> hits = service.on(LocalDate.of(2026, 11, 1), new CalendarPlace("LU", null, null));

        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.kind()).isEqualTo("holiday");
            assertThat(h.name()).isEqualTo("All Saints");
            assertThat(h.region()).isEmpty();
            assertThat(h.endDate()).isNull();
            assertThat(h.sourceUrl()).isEqualTo(OpenHolidaysSync.url("LU", 2026));
            assertThat(h.origin()).isEqualTo(CalendarHit.SYNCED);
        });
        assertThat(service.covers("LU", "holiday", LocalDate.of(2026, 3, 1))).isTrue();
    }

    @Test
    void subdivisionRowsStoredPerCode() {
        List<CalendarRow> de = rows("DE", CalendarFixtures.text("openholidays-DE-2026.json"));

        assertThat(de).filteredOn(r -> r.date().equals(LocalDate.of(2026, 11, 1)))
                .extracting(CalendarRow::region, CalendarRow::name)
                .containsExactlyInAnyOrder(tuple("DE-SL", "All Saints' Day"), tuple("DE-NW", "All Saints' Day"),
                        tuple("DE-BW", "All Saints' Day"), tuple("DE-RP", "All Saints' Day"),
                        tuple("DE-BY", "All Saints' Day"));
        // Labour Day is a full day in Basel-Stadt and Basel-Landschaft; the Solothurn afternoon-only row is dropped
        List<CalendarRow> ch = rows("CH", CalendarFixtures.text("openholidays-CH-2026.json"));
        assertThat(ch).filteredOn(r -> r.date().equals(LocalDate.of(2026, 5, 1)))
                .extracting(CalendarRow::region).contains("CH-BS", "CH-BL").doesNotContain("", "CH-SO-TH-WG");
    }

    @Test
    void nationwideRowStoredOnceWithEmptyRegion() {
        List<CalendarRow> de = rows("DE", CalendarFixtures.text("openholidays-DE-2026.json"));

        assertThat(de).filteredOn(r -> r.date().equals(LocalDate.of(2026, 1, 1))).containsExactly(
                new CalendarRow("DE", "", LocalDate.of(2026, 1, 1), null, "holiday", "New Year's Day",
                        OpenHolidaysSync.url("DE", 2026)));
    }

    @Test
    void onlyFullDayPublicHolidaysAboveCommuneLevelStored() {
        // recorded: LU Good Friday 2026 is type Bank
        List<CalendarRow> lu = rows("LU", CalendarFixtures.text("openholidays-LU-2026.json"));
        assertThat(lu).hasSize(11).extracting(CalendarRow::date).doesNotContain(LocalDate.of(2026, 4, 3));

        // synthetic: one row per excluded shape, then one kept regional row
        List<CalendarRow> rows = rows("CH", "["
                + "{\"type\":\"Optional\",\"temporalScope\":\"FullDay\",\"regionalScope\":\"Regional\","
                + "\"startDate\":\"2026-03-02\",\"endDate\":\"2026-03-02\",\"nationwide\":false,"
                + "\"name\":[{\"language\":\"EN\",\"text\":\"Optional\"}],\"subdivisions\":[{\"code\":\"CH-BS\"}]},"
                + "{\"type\":\"Public\",\"temporalScope\":\"HalfDay\",\"regionalScope\":\"Regional\","
                + "\"startDate\":\"2026-03-03\",\"endDate\":\"2026-03-03\",\"nationwide\":false,"
                + "\"name\":[{\"language\":\"EN\",\"text\":\"HalfDay\"}],\"subdivisions\":[{\"code\":\"CH-BS\"}]},"
                + "{\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"regionalScope\":\"Local\","
                + "\"startDate\":\"2026-03-04\",\"endDate\":\"2026-03-04\",\"nationwide\":false,"
                + "\"name\":[{\"language\":\"EN\",\"text\":\"Local\"}],\"subdivisions\":[{\"code\":\"CH-BS-RI\"}]},"
                + "{\"type\":\"Bank\",\"temporalScope\":\"FullDay\",\"regionalScope\":\"National\","
                + "\"startDate\":\"2026-03-05\",\"endDate\":\"2026-03-05\",\"nationwide\":true,"
                + "\"name\":[{\"language\":\"EN\",\"text\":\"Bank\"}]},"
                + "{\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"regionalScope\":\"Regional\","
                + "\"startDate\":\"2026-03-06\",\"endDate\":\"2026-03-06\",\"nationwide\":false,"
                + "\"name\":[{\"language\":\"EN\",\"text\":\"Kept\"}],\"subdivisions\":[{\"code\":\"CH-BS\"}]}]");

        assertThat(rows).extracting(CalendarRow::name, CalendarRow::region).containsExactly(tuple("Kept", "CH-BS"));
    }

    @Test
    void overlongSubdivisionCodeSkipped() {
        // synthetic: a code over the 16-char region column is skipped, never truncated
        List<CalendarRow> rows = rows("CH", "[{\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-01\",\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"nationwide\":false,"
                + "\"name\":[{\"language\":\"EN\",\"text\":\"Local Day\"}],"
                + "\"subdivisions\":[{\"code\":\"CH-NE\"},{\"code\":\"CH-NE-XX-YY-ZZ-WW\"}]}]");

        assertThat(rows).extracting(CalendarRow::region).containsExactly("CH-NE");
    }

    @Test
    void rowWithoutSubdivisionsSkipped() {
        // synthetic: a regional row naming no subdivision has nowhere to be stored
        List<CalendarRow> rows = rows("ES", "["
                + "{\"startDate\":\"2026-03-19\",\"endDate\":\"2026-03-19\",\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"nationwide\":false,"
                + "\"name\":[{\"language\":\"EN\",\"text\":\"Saint Joseph\"}]},"
                + "{\"startDate\":\"2026-03-20\",\"endDate\":\"2026-03-20\",\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"nationwide\":false,\"subdivisions\":[],"
                + "\"name\":[{\"language\":\"EN\",\"text\":\"Empty\"}]},"
                + "{\"startDate\":\"2026-01-06\",\"endDate\":\"2026-01-06\",\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"nationwide\":true,"
                + "\"name\":[{\"language\":\"EN\",\"text\":\"Epiphany\"}]}]");

        assertThat(rows).extracting(CalendarRow::name).containsExactly("Epiphany");
    }

    @Test
    void englishNameChosen() {
        // synthetic: EN wins over its position; with no EN entry the first one is used
        List<CalendarRow> rows = rows("BE", "["
                + "{\"startDate\":\"2026-07-21\",\"endDate\":\"2026-07-21\",\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"nationwide\":true,"
                + "\"name\":[{\"language\":\"FR\",\"text\":\"Fête nationale\"},{\"language\":\"EN\",\"text\":\"National Day\"}]},"
                + "{\"startDate\":\"2026-07-11\",\"endDate\":\"2026-07-11\",\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"nationwide\":true,"
                + "\"name\":[{\"language\":\"NL\",\"text\":\"Vlaamse feestdag\"},{\"language\":\"FR\",\"text\":\"Fête flamande\"}]}]");

        assertThat(rows).extracting(CalendarRow::name).containsExactly("National Day", "Vlaamse feestdag");
    }

    @Test
    void multiDayHolidayKeepsEndDate() {
        // synthetic: every recorded 2026 row is one day long, but the API models ranges
        List<CalendarRow> rows = rows("PT", "[{\"startDate\":\"2026-12-24\",\"endDate\":\"2026-12-26\","
                + "\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"nationwide\":true,\"name\":[{\"language\":\"EN\",\"text\":\"Christmas\"}]}]");

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.date()).isEqualTo(LocalDate.of(2026, 12, 24));
            assertThat(r.endDate()).isEqualTo(LocalDate.of(2026, 12, 26));
        });
    }

    /** Rows: an upstream 500, an empty array and a non-array body; the writer keeps prior rows (its own test). */
    @ParameterizedTest
    @ValueSource(strings = {"500", "[]", "{\"error\":\"rate limited\"}"})
    void failedAnswerGivesNoBatch(String answer) {
        server.reset();
        server.expect(requestTo(OpenHolidaysSync.url("LU", 2026))).andRespond(answer.equals("500")
                ? withServerError() : withSuccess(answer, MediaType.APPLICATION_JSON));

        List<CalendarSource.Batch> batches =
                new OpenHolidaysSync(builder.build(), CalendarFixtures.props(0), List.of("LU")).fetch(TODAY);

        server.verify();
        assertThat(batches).isEmpty();
    }

    @Test
    void nlSyncedRowsReplaceFallback() {
        LocalDate kingsDay = LocalDate.of(2026, 4, 27);
        CalendarPlace nl = new CalendarPlace("NL", null, null);
        assertThat(service.on(kingsDay, nl)).singleElement()
                .satisfies(h -> assertThat(h.origin()).isEqualTo(CalendarHit.FALLBACK));

        // synthetic one-row answer; one synced holiday is enough to retire the static table for NL 2026
        fetch("NL", "[{\"startDate\":\"2026-04-27\",\"endDate\":\"2026-04-27\",\"type\":\"Public\",\"temporalScope\":\"FullDay\",\"nationwide\":true,"
                + "\"name\":[{\"language\":\"EN\",\"text\":\"King's Day\"}]}]").forEach(writer::replace);

        assertThat(service.on(kingsDay, nl)).singleElement().satisfies(h -> {
            assertThat(h.origin()).isEqualTo(CalendarHit.SYNCED);
            assertThat(h.name()).isEqualTo("King's Day");
            assertThat(h.sourceUrl()).isEqualTo(OpenHolidaysSync.url("NL", 2026));
        });
    }
}
