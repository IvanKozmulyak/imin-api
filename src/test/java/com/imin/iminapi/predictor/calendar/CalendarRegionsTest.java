package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBankLoader;
import com.imin.iminapi.util.EventNormalization;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
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

    @Test
    void everyBank45CityHasNeighbours() {
        QuestionBank.Question q45 = QuestionBankLoader.load(new DefaultResourceLoader()).questionsFor("FR").stream()
                .filter(q -> q.id().equals("4.5")).findFirst().orElseThrow();

        assertThat(q45.cities()).isNotEmpty();
        for (String city : q45.cities()) {
            assertThat(CalendarRegions.neighbours(city)).as(city).isNotEmpty();
        }
        assertThat(CalendarRegions.BORDER_NEIGHBOURS).containsOnlyKeys(
                q45.cities().stream().map(EventNormalization::cityKey).toList());
        assertThat(CalendarRegions.neighbours("Metz")).isEqualTo(Map.of("LU", List.of(""), "DE", List.of("DE-SL", "DE-RP")));
        assertThat(CalendarRegions.neighbours(" MULHOUSE ")).containsOnlyKeys("DE", "CH");
        assertThat(CalendarRegions.neighbours("Paris")).isEmpty();
        assertThat(CalendarRegions.neighbours(null)).isEmpty();
    }
}
