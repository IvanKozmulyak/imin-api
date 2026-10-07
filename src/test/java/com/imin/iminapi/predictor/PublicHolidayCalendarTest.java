package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.service.PublicHolidayCalendar;
import com.imin.iminapi.predictor.service.PublicHolidayCalendar.Holiday;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicHolidayCalendarTest {

    private static final LocalDate GOOD_FRIDAY_2027 = LocalDate.of(2027, 3, 26);

    @Test
    void parisGoodFriday2027IsNotHoliday() {
        assertThat(PublicHolidayCalendar.regionOf("FR", "75001", "Paris")).isNull();
        assertThat(PublicHolidayCalendar.near("FR", null, GOOD_FRIDAY_2027, 0)).isEmpty();
    }

    @Test
    void unknownRegionFallsBackToNational() {
        LocalDate xmas = LocalDate.of(2026, 12, 25);
        List<Holiday> national = PublicHolidayCalendar.near("FR", xmas, 1);
        assertThat(national).containsExactly(new Holiday(xmas, "Noël"));
        assertThat(PublicHolidayCalendar.near("FR", "FR-75", xmas, 1)).isEqualTo(national);
    }

    @Test
    void regionOfAnotherCountryIsIgnored() {
        // ES has its own national Good Friday that day; only the FR-57 row must not leak in.
        assertThat(PublicHolidayCalendar.near("ES", "FR-57", GOOD_FRIDAY_2027, 0))
                .isEqualTo(PublicHolidayCalendar.near("ES", GOOD_FRIDAY_2027, 0))
                .containsExactly(new Holiday(GOOD_FRIDAY_2027, "Good Friday"));
        assertThat(PublicHolidayCalendar.near("ES", "FR-57", LocalDate.of(2026, 12, 26), 0)).isEmpty();
    }

    @Test
    void regionalAndNationalMergeInDateOrder() {
        LocalDate xmas = LocalDate.of(2026, 12, 25);
        assertThat(PublicHolidayCalendar.near("FR", "FR-68", xmas, 1)).containsExactly(
                new Holiday(xmas, "Noël"),
                new Holiday(LocalDate.of(2026, 12, 26), "Saint-Étienne"));
    }

    @Test
    void regionalRowsNeedCoverage() {
        assertThat(PublicHolidayCalendar.near("FR", "FR-57", LocalDate.of(2028, 4, 14), 0)).isEmpty();
    }

    // Metz by postcode is owned by CalendarRegionsTest.metzPostcode; these are the branches it does not reach.
    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource(nullValues = "null", value = {
            "FR, 67000, Strasbourg, FR-67",
            "FR, 68100, Mulhouse, FR-68",
            "FR, ' 57 000 ', Metz, FR-57",
            "FR, 75011, Metz, null",
            "FR, '', ' METZ ', FR-57",
            "FR, null, Colmar, FR-68",
            "FR, F-57000, Metz, FR-57",
            "FR, '', Lyon, null",
            "FR, null, null, null",
            "DE, 67000, Strasbourg, null",
            "null, 57000, Metz, null",
    })
    void regionOfResolvesAlsaceMoselleDepartment(String country, String postcode, String city, String region) {
        assertThat(PublicHolidayCalendar.regionOf(country, postcode, city)).isEqualTo(region);
    }
}
