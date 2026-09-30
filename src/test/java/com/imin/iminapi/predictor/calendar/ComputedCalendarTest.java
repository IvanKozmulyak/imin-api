package com.imin.iminapi.predictor.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComputedCalendarTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final String URL = "https://calendrier.api.gouv.fr/jours-feries/metropole/2027.json";

    private static CalendarRow holiday(String date, String name) {
        return new CalendarRow("FR", "", LocalDate.parse(date), null, "holiday", name, URL);
    }

    @Test
    void ascension2027MakesFriday7MayAPont() {
        List<CalendarRow> ponts = ComputedCalendar.ponts(List.of(holiday("2027-05-06", "Ascension")));

        assertThat(ponts).containsExactly(new CalendarRow("FR", "", LocalDate.of(2027, 5, 7), null,
                "pont", "pont:Ascension", URL));
    }

    @Test
    void tuesdayHolidayMakesMondayPont() {
        // 2026-12-01 is a Tuesday
        List<CalendarRow> ponts = ComputedCalendar.ponts(List.of(holiday("2026-12-01", "Test")));

        assertThat(ponts).extracting(CalendarRow::date).containsExactly(LocalDate.of(2026, 11, 30));
    }

    @Test
    void wednesdayHolidayMakesNoPont() {
        // 2027-07-14 is a Wednesday
        assertThat(ComputedCalendar.ponts(List.of(holiday("2027-07-14", "14 juillet")))).isEmpty();
    }

    @Test
    void pontSkippedWhenDayIsHoliday() {
        // Thursday 2026-12-24 would make Friday 25 a pont, but 25 is itself a holiday
        List<CalendarRow> ponts = ComputedCalendar.ponts(List.of(
                holiday("2026-12-24", "Réveillon"), holiday("2026-12-25", "Jour de Noël")));

        assertThat(ponts).isEmpty();
    }

    @Test
    void springDstNight2027Is23Hours() {
        List<ComputedCalendar.DstNight> nights =
                ComputedCalendar.dstNights(PARIS, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30));

        assertThat(nights).containsExactly(new ComputedCalendar.DstNight(LocalDate.of(2027, 3, 27), 23));
        assertThat(nights.get(0).name()).isEqualTo("dst_forward");
    }

    @Test
    void autumnDstNight2026Is25Hours() {
        List<ComputedCalendar.DstNight> nights =
                ComputedCalendar.dstNights(PARIS, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));

        assertThat(nights).containsExactly(new ComputedCalendar.DstNight(LocalDate.of(2026, 10, 24), 25));
        assertThat(nights.get(0).name()).isEqualTo("dst_back");
    }

    @Test
    void eidAlFitr2027Within1DayOfAladhanReference() {
        // Aladhan hToG for 1-9, 1-10 and 10-12 of 1448 AH, fetched 2026-09-30
        List<CalendarRow> rows = ComputedCalendar.hijri(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));

        CalendarRow fitr = byName(rows, "eid_al_fitr");
        CalendarRow ramadan = byName(rows, "ramadan");
        CalendarRow adha = byName(rows, "eid_al_adha");
        assertThat(Math.abs(ChronoUnit.DAYS.between(LocalDate.of(2027, 3, 9), fitr.date()))).isLessThanOrEqualTo(1);
        assertThat(Math.abs(ChronoUnit.DAYS.between(LocalDate.of(2027, 2, 8), ramadan.date()))).isLessThanOrEqualTo(1);
        assertThat(Math.abs(ChronoUnit.DAYS.between(LocalDate.of(2027, 5, 16), adha.date()))).isLessThanOrEqualTo(1);
        assertThat(ramadan.endDate()).isEqualTo(fitr.date().minusDays(1));
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.kind()).isEqualTo("hijri");
            assertThat(r.sourceUrl()).isEqualTo(ComputedCalendar.HIJRI_URL);
        });
    }

    @Test
    void fetchWritesFrenchDstAndHijriBatchesOverTheYearsAhead() {
        List<CalendarSource.Batch> batches =
                new ComputedCalendar(CalendarFixtures.props(1)).fetch(LocalDate.of(2026, 9, 30));

        assertThat(batches).hasSize(2);
        CalendarSource.Batch dst = batches.get(0);
        assertThat(dst.sourceUrl()).isEqualTo(ComputedCalendar.IANA_URL);
        assertThat(dst.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(dst.to()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(dst.rows()).hasSize(4).allSatisfy(r -> {
            assertThat(r.country()).isEqualTo("FR");
            assertThat(r.region()).isEmpty();
        });
        assertThat(batches.get(1).kinds()).containsExactly("hijri");
        assertThat(batches.get(1).rows()).isNotEmpty().allSatisfy(r -> assertThat(r.country()).isEqualTo("FR"));
    }

    private static CalendarRow byName(List<CalendarRow> rows, String name) {
        return rows.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }
}
