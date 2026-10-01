package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional
class ReferenceCalendarServiceTest {

    private static final CalendarPlace PARIS = new CalendarPlace("FR", null, "FR-ZC");
    private static final CalendarPlace METZ = new CalendarPlace("FR", "FR-57", "FR-ZB");

    @Autowired ReferenceCalendarEntryRepository repository;
    @Autowired ReferenceCalendarService service;

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

    @Test
    void latestReturnsNewestFixtureDate() {
        store("", "2026-10-10", null, "fixture", "FL1|20:45|525|1045|Lorient – Paris FC");
        store("", "2027-05-09", null, "fixture", "FL1|20:45|523|524|Olympique Lyon – PSG");
        store("", "2027-12-25", null, "holiday", "Noël");

        assertThat(service.latest("FR", "fixture")).contains(LocalDate.of(2027, 5, 9));
        assertThat(service.latest("FR", "holiday")).contains(LocalDate.of(2027, 12, 25));
    }

    @Test
    void latestCountsARangesEndDate() {
        store("", "2027-05-09", null, "fixture", "FL1|20:45|523|524|Olympique Lyon – PSG");
        store("", "2027-05-21", "2027-05-23", "fixture", "FL1|TBC|511|524|Toulouse – PSG");

        assertThat(service.latest("FR", "fixture")).contains(LocalDate.of(2027, 5, 23));
    }

    @Test
    void lastSyncedIsTheNewestWriteOfTheKind() {
        java.time.Instant before = java.time.Instant.now().minusSeconds(5);
        store("", "2027-05-09", null, "fixture", "FL1|20:45|523|524|Olympique Lyon – PSG");

        assertThat(service.lastSynced("FR", "fixture")).hasValueSatisfying(t -> assertThat(t).isAfter(before));
        assertThat(service.lastSynced("FR", "dst")).isEmpty();
        assertThat(service.lastSynced("NL", "fixture")).isEmpty();
    }

    @Test
    void latestEmptyWhenNoRows() {
        store("", "2027-12-25", null, "holiday", "Noël");

        assertThat(service.latest("FR", "fixture")).isEmpty();
        assertThat(service.latest("NL", "holiday")).isEmpty();
    }
}
