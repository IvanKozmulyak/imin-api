package com.imin.iminapi.audienceplan.opendata;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
    void everyCityHasEveryDatasetOnce() {
        for (OpenDataCity city : cities.all()) {
            for (OpenDataset d : OpenDataset.values()) {
                assertThat(rows.stream().filter(r -> r.cityKey().equals(city.cityKey()) && r.dataset() == d))
                        .as(city.cityKey() + " " + d.key()).hasSize(1);
            }
        }
    }

    @Test
    void metzMatchesTheVerifiedFigures() {
        assertThat(row("metz", OpenDataset.INSEE_AGE).headline()).isEqualTo(38_065L);
        assertThat(row("metz", OpenDataset.INSEE_AGE).figures()).containsEntry("pop_total", 122_572L);
        assertThat(row("metz", OpenDataset.INSEE_AGE).refPeriod()).isEqualTo("2023");
        assertThat(row("metz", OpenDataset.STUDENTS).headline()).isEqualTo(20_588L);
        assertThat(row("metz", OpenDataset.STUDENTS).refPeriod()).isEqualTo("2024-25");
        assertThat(row("metz", OpenDataset.FRONTALIERS).headline()).isEqualTo(6_330L);
        assertThat(row("metz", OpenDataset.FRONTALIERS).refPeriod()).isEqualTo("2026-03-31");
    }

    @Test
    void sourceUrlsAreTheOnesTheFetchersSend() {
        for (OpenDataCity city : cities.all()) {
            assertThat(row(city.cityKey(), OpenDataset.INSEE_AGE).sourceUrl()).isEqualTo(InseeMelodiFetcher.url(city));
            assertThat(row(city.cityKey(), OpenDataset.STUDENTS).sourceUrl()).isEqualTo(MesrAtlasFetcher.url(city));
            assertThat(row(city.cityKey(), OpenDataset.FRONTALIERS).sourceUrl()).isEqualTo(IgssFrontaliersFetcher.URL);
        }
    }

    @Test
    void osmRowsAreCountsOnlyWithNoHeadline() {
        OpenDataSeed.Row metz = row("metz", OpenDataset.OSM_VENUES);
        assertThat(metz.headline()).isNull();
        assertThat(metz.figures()).containsEntry("nightclub", 3L).containsEntry("bar", 69L)
                .containsEntry("pub", 14L).containsEntry("music_venue", 1L);
        assertThat(OpenDataset.OSM_VENUES.licence()).isEqualTo("ODbL 1.0");
        assertThat(OpenDataset.OSM_VENUES.attribution()).isEqualTo("© OpenStreetMap contributors");
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
