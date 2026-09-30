package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarRegionsTest {

    private final CalendarRegions regions = new CalendarRegions(OpenDataCities.load());

    @Test
    void metzPostcode() {
        CalendarPlace p = regions.of("FR", "57000", "Metz");

        assertThat(p).isEqualTo(new CalendarPlace("FR", "FR-57", "FR-ZB"));
        assertThat(p.regions()).containsExactly("", "FR-57", "FR-ZB");
    }

    @Test
    void metzCityWithoutPostcode() {
        assertThat(regions.of("fr", null, "Metz")).isEqualTo(new CalendarPlace("FR", "FR-57", "FR-ZB"));
    }

    @Test
    void zoneFromTheOpenDataCityListWithoutPostcode() {
        // Nancy INSEE 54395 → département 54, académie Nancy-Metz
        assertThat(regions.of("FR", "", "Nancy")).isEqualTo(new CalendarPlace("FR", null, "FR-ZB"));
    }

    @Test
    void parisZoneC() {
        CalendarPlace p = regions.of("FR", "75011", "Paris");

        assertThat(p).isEqualTo(new CalendarPlace("FR", null, "FR-ZC"));
        assertThat(p.regions()).containsExactly("", "FR-ZC");
    }

    @Test
    void corsicaHasNoZone() {
        assertThat(regions.of("FR", "20000", "Ajaccio")).isEqualTo(new CalendarPlace("FR", null, null));
        assertThat(regions.of("FR", "97400", "Saint-Denis")).isEqualTo(new CalendarPlace("FR", null, null));
    }

    @Test
    void unknownFrenchPlaceIsOnlyNational() {
        assertThat(regions.of("FR", null, "Nowhere").regions()).containsExactly("");
    }

    @Test
    void nonFrOnlyNational() {
        CalendarPlace p = regions.of("LU", "1111", "Luxembourg");

        assertThat(p).isEqualTo(new CalendarPlace("LU", null, null));
        assertThat(p.regions()).containsExactly("");
    }

    @Test
    void everyMetropolitanDepartementHasOneZone() {
        List<String> depts = IntStream.rangeClosed(1, 95).filter(i -> i != 20)
                .mapToObj(i -> String.format("%02d", i)).toList();

        assertThat(CalendarRegions.SCHOOL_ZONE_BY_DEPT).containsOnlyKeys(depts);
    }
}
