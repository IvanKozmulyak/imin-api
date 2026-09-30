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
    void metzGoodFriday2027IsHoliday() {
        assertThat(PublicHolidayCalendar.near("FR", "FR-57", GOOD_FRIDAY_2027, 0))
                .containsExactly(new Holiday(GOOD_FRIDAY_2027, "Vendredi saint"));
    }

    @Test
    void otherRegionalRowsExist() {
        LocalDate goodFriday2026 = LocalDate.of(2026, 4, 3);
        LocalDate stEtienne2027 = LocalDate.of(2027, 12, 26);
        assertThat(PublicHolidayCalendar.near("FR", "FR-67", goodFriday2026, 0))
                .containsExactly(new Holiday(goodFriday2026, "Vendredi saint"));
        assertThat(PublicHolidayCalendar.near("FR", "FR-68", stEtienne2027, 0))
                .containsExactly(new Holiday(stEtienne2027, "Saint-Étienne"));
    }

    @Test
    void parisGoodFriday2027IsNotHoliday() {
        assertThat(PublicHolidayCalendar.regionOf("FR", "75001", "Paris")).isNull();
        assertThat(PublicHolidayCalendar.near("FR", null, GOOD_FRIDAY_2027, 0)).isEmpty();
    }

    @Test
    void strasbourgBoxingDay2026IsHoliday() {
        assertThat(PublicHolidayCalendar.regionOf("FR", "67000", "Strasbourg")).isEqualTo("FR-67");
        LocalDate d = LocalDate.of(2026, 12, 26);
        assertThat(PublicHolidayCalendar.near("FR", "FR-67", d, 0))
                .containsExactly(new Holiday(d, "Saint-Étienne"));
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

    @ParameterizedTest
    @CsvSource({
            "57000, Metz, FR-57",
            "67000, Strasbourg, FR-67",
            "68100, Mulhouse, FR-68",
            "' 57 000 ', Metz, FR-57",
    })
    void postcodeResolvesDepartment(String postcode, String city, String region) {
        assertThat(PublicHolidayCalendar.regionOf("FR", postcode, city)).isEqualTo(region);
    }

    @Test
    void postcodeOutsideAlsaceMoselleWinsOverCity() {
        assertThat(PublicHolidayCalendar.regionOf("FR", "75011", "Metz")).isNull();
    }

    @Test
    void blankOrMalformedPostcodeFallsBackToCity() {
        assertThat(PublicHolidayCalendar.regionOf("FR", "", " METZ ")).isEqualTo("FR-57");
        assertThat(PublicHolidayCalendar.regionOf("FR", null, "Colmar")).isEqualTo("FR-68");
        assertThat(PublicHolidayCalendar.regionOf("FR", "F-57000", "Metz")).isEqualTo("FR-57");
    }

    @Test
    void unknownCityWithoutPostcodeHasNoRegion() {
        assertThat(PublicHolidayCalendar.regionOf("FR", "", "Lyon")).isNull();
        assertThat(PublicHolidayCalendar.regionOf("FR", null, null)).isNull();
    }

    @Test
    void nonFrenchCountryHasNoRegion() {
        assertThat(PublicHolidayCalendar.regionOf("DE", "67000", "Strasbourg")).isNull();
        assertThat(PublicHolidayCalendar.regionOf(null, "57000", "Metz")).isNull();
    }

    @Test
    void existingThreeArgNearIsUnchanged() {
        assertThat(PublicHolidayCalendar.near("FR", GOOD_FRIDAY_2027, 0)).isEmpty();
    }
}
