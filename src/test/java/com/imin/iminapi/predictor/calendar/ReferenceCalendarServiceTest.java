package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@IminIntegrationTest
@Transactional
class ReferenceCalendarServiceTest {

    private static final CalendarPlace PARIS = new CalendarPlace("FR", null, "FR-ZC");
    private static final CalendarPlace METZ = new CalendarPlace("FR", "FR-57", "FR-ZB");

    @Autowired ReferenceCalendarEntryRepository repository;
    @Autowired ReferenceCalendarService service;
    @Autowired JdbcTemplate jdbc;

    private void store(String region, String date, String end, String kind, String name) {
        ReferenceCalendarEntry e = new ReferenceCalendarEntry();
        e.setCountry("FR");
        e.setRegion(region);
        e.setCalendarDate(LocalDate.parse(date));
        e.setEndDate(end == null ? null : LocalDate.parse(end));
        e.setKind(kind);
        e.setName(name);
        e.setSourceUrl("https://src.test/" + name);
        repository.saveAndFlush(e);
    }

    /** A synced national holiday that year, so no fallback rows join the answer. */
    private void syncedYear(int year) {
        store("", year + "-01-01", null, "holiday", "1er janvier");
    }

    @Test
    void rangeStartingBeforeFromIsReturned() {
        syncedYear(2026);
        store("FR-ZC", "2026-10-17", "2026-11-01", "school", "Vacances de la Toussaint");

        List<CalendarHit> hits = service.on(LocalDate.of(2026, 10, 25), PARIS);

        assertThat(hits).singleElement().isEqualTo(new CalendarHit(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 11, 1),
                "school", "Vacances de la Toussaint", "FR-ZC", "https://src.test/Vacances de la Toussaint",
                false, CalendarHit.SYNCED));
        assertThat(service.on(LocalDate.of(2026, 11, 2), PARIS)).isEmpty();
    }

    @Test
    void regionRowsOnlyForMatchingRegion() {
        store("", "2027-03-29", null, "holiday", "Lundi de Pâques");
        store("FR-57", "2027-03-26", null, "holiday", "Vendredi saint");

        List<CalendarHit> metz = service.between(LocalDate.of(2027, 3, 25), LocalDate.of(2027, 3, 30), METZ);
        List<CalendarHit> paris = service.between(LocalDate.of(2027, 3, 25), LocalDate.of(2027, 3, 30), PARIS);

        assertThat(metz).extracting(CalendarHit::name, CalendarHit::region).containsExactly(
                tuple("Vendredi saint", "FR-57"),
                tuple("Lundi de Pâques", ""));
        assertThat(paris).extracting(CalendarHit::name).containsExactly("Lundi de Pâques");
    }

    @Test
    void syncedHolidaysReplaceTheStaticTable() {
        // the static table has 14 July 2027; a synced year without it must not bring it back
        syncedYear(2027);

        assertThat(service.on(LocalDate.of(2027, 7, 14), PARIS)).isEmpty();
        assertThat(service.covers("FR", "holiday", LocalDate.of(2027, 7, 14))).isTrue();
        assertThat(service.covers("FR", "school", LocalDate.of(2027, 7, 14))).isFalse();
    }

    @Test
    void fallbackWhenCountryHasNoSyncedRows() {
        List<CalendarHit> hits = service.on(LocalDate.of(2026, 8, 24), new CalendarPlace("UA", null, null));

        assertThat(hits).containsExactly(new CalendarHit(LocalDate.of(2026, 8, 24), null, "holiday",
                "Independence Day", "", null, false, CalendarHit.FALLBACK));
        assertThat(service.covers("UA", "holiday", LocalDate.of(2026, 8, 24))).isFalse();
    }

    @Test
    void fallbackRegionalRowCarriesTheRegion() {
        List<CalendarHit> hits = service.between(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 26), METZ);

        assertThat(hits).extracting(CalendarHit::name, CalendarHit::region, CalendarHit::origin).containsExactly(
                tuple("Noël", "", CalendarHit.FALLBACK),
                tuple("Saint-Étienne", "FR-57", CalendarHit.FALLBACK));
    }

    @Test
    void hijriHitIsApproximate() {
        syncedYear(2027);
        store("", "2027-03-09", null, "hijri", "eid_al_fitr");

        assertThat(service.on(LocalDate.of(2027, 3, 9), PARIS)).singleElement()
                .satisfies(h -> assertThat(h.approximate()).isTrue());
    }

    /**
     * latest() is the newest covered day of a country's kind (a range counts its end date); lastSynced() is the
     * newest write. Other kinds and countries never answer. The queried scopes' committed rows are hidden in
     * this test's rolled-back transaction, so only the seeded rows count.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
            "newest fixture date;   fixture:2026-10-10 fixture:2027-05-09 holiday:2027-12-25; latest;     FR; fixture; 2027-05-09",
            "newest holiday date;   fixture:2026-10-10 fixture:2027-05-09 holiday:2027-12-25; latest;     FR; holiday; 2027-12-25",
            "range counts its end;  fixture:2027-05-09 fixture:2027-05-21..2027-05-23;       latest;     FR; fixture; 2027-05-23",
            "no fixture rows;       holiday:2027-12-25;                                       latest;     FR; fixture; NULL",
            "no rows in country;    holiday:2027-12-25;                                       latest;     NL; holiday; NULL",
            "newest write;          fixture:2027-05-09;                                       lastSynced; FR; fixture; NOW",
            "no write of the kind;  fixture:2027-05-09;                                       lastSynced; FR; dst;     NULL",
            "no write in country;   fixture:2027-05-09;                                       lastSynced; NL; fixture; NULL"},
            delimiter = ';', nullValues = "NULL")
    void latestAndLastSyncedReadOnlyTheirScope(String name, String seed, String method, String country, String kind,
                                               String expected) {
        for (String c : List.of("FR", "NL")) {
            jdbc.update("DELETE FROM reference_calendar WHERE country = ? AND kind IN ('fixture', 'holiday', 'dst')", c);
        }
        Instant before = Instant.now().minusSeconds(5);
        int n = 0;
        for (String row : seed.split(" ")) {
            String[] kindAndDates = row.split(":");
            String[] dates = kindAndDates[1].split("\\.\\.");
            store("", dates[0], dates.length > 1 ? dates[1] : null, kindAndDates[0], "row " + n++);
        }

        if (method.equals("latest")) {
            assertThat(service.latest(country, kind)).isEqualTo(Optional.ofNullable(expected).map(LocalDate::parse));
        } else if (expected == null) {
            assertThat(service.lastSynced(country, kind)).isEmpty();
        } else {
            assertThat(service.lastSynced(country, kind)).hasValueSatisfying(t -> assertThat(t).isAfter(before));
        }
    }
}
