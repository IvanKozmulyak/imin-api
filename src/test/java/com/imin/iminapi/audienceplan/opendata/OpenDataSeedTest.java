package com.imin.iminapi.audienceplan.opendata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenDataSeedTest {

    private final List<OpenDataSeed.Row> rows = OpenDataSeed.load();
    private final OpenDataCities cities = OpenDataCities.load();

    @Test
    void everyRowIsForAKnownCityWithSourceAndPeriod() {
        assertThat(rows).isNotEmpty();
        for (OpenDataSeed.Row r : rows) {
            assertThat(cities.find(r.cityKey())).as(r.cityKey()).isPresent();
            assertThat(r.refPeriod()).isNotBlank();
            assertThat(r.sourceUrl()).startsWith("https://");
            assertThat(r.fetchedAt()).isEqualTo(Instant.parse("2026-09-27T00:00:00Z"));
        }
    }

    @Test
    void everyCityHasEveryDatasetCoveringItsCountryOnceAndNoOther() {
        for (OpenDataCity city : cities.all()) {
            for (OpenDataset d : OpenDataset.values()) {
                assertThat(rows.stream().filter(r -> r.cityKey().equals(city.cityKey()) && r.dataset() == d))
                        .as(city.cityKey() + " " + d.key()).hasSize(d.covers(city.country()) ? 1 : 0);
            }
        }
    }

    @Test
    void sourceUrlsAreTheOnesTheFetchersSend() {
        for (OpenDataCity city : cities.all()) {
            if (!"FR".equals(city.country())) continue;
            assertThat(row(city.cityKey(), OpenDataset.INSEE_AGE).sourceUrl()).isEqualTo(InseeMelodiFetcher.url(city));
            assertThat(row(city.cityKey(), OpenDataset.STUDENTS).sourceUrl()).isEqualTo(MesrAtlasFetcher.url(city));
            assertThat(row(city.cityKey(), OpenDataset.FRONTALIERS).sourceUrl()).isEqualTo(IgssFrontaliersFetcher.URL);
        }
    }

    @Test
    void osmRowsHaveNoHeadline() {
        assertThat(row("metz", OpenDataset.OSM_VENUES).headline()).isNull();
    }

    @Test
    void centroidsHaveNoHeadlineAndCiteWikidata() {
        for (OpenDataSeed.Row r : rows) {
            if (r.dataset() != OpenDataset.CENTROID) continue;
            assertThat(r.headline()).as(r.cityKey()).isNull();
            assertThat(r.sourceUrl()).as(r.cityKey()).startsWith("https://www.wikidata.org/");
        }
    }

    @Test
    void townsAbroadAreRegisteredWithoutFrenchCodes() {
        assertThat(cities.find("luxembourg")).hasValueSatisfying(c -> {
            assertThat(c.country()).isEqualTo("LU");
            assertThat(c.inseeCode()).isNull();
            assertThat(c.department()).isNull();
        });
        assertThat(cities.find("saarbrücken")).hasValueSatisfying(c -> assertThat(c.country()).isEqualTo("DE"));
    }

    @Test
    void cityKeysAreTheEventCityKeysOfTheirNames() {
        assertThat(cities.find("metz")).hasValueSatisfying(c -> {
            assertThat(c.inseeCode()).isEqualTo("57463");
            assertThat(c.department()).isEqualTo("Moselle");
        });
        assertThat(cities.find("Metz")).isEmpty();
        assertThat(cities.find(null)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(OpenDataset.class)
    void dataset_keysAndCountryCoverage(OpenDataset d) {
        assertThat(OpenDataset.fromKey(d.key())).contains(d);
        assertThat(OpenDataset.fromKey("sirene")).isEmpty();
        // French sources cover France only; centroids also cover the border countries.
        Set<String> covered = d == OpenDataset.CENTROID ? Set.of("FR", "LU", "DE") : Set.of("FR");
        for (String country : List.of("FR", "LU", "DE", "BE")) {
            assertThat(d.covers(country)).as(d.key() + " " + country).isEqualTo(covered.contains(country));
        }
        assertThat(d.covers(null)).isFalse();
    }

    private OpenDataSeed.Row row(String city, OpenDataset d) {
        return rows.stream().filter(r -> r.cityKey().equals(city) && r.dataset() == d).findFirst().orElseThrow();
    }

    @Test
    void aSeedRowWithoutACityFailsTheLoad() {
        assertThatThrownBy(() -> OpenDataSeed.rows(OpenDataset.STUDENTS, "audienceplan/open-data-test/students_no_city.csv"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("without city_key");
    }

    @Test
    void aBlankFigureIsNullNeverZero() {
        OpenDataSeed.Row r = OpenDataSeed.rows(OpenDataset.STUDENTS, "audienceplan/open-data-test/students_blank_figure.csv").get(0);

        assertThat(r.headline()).isNull();
        assertThat(r.figures()).containsEntry("students", null);
    }
}
