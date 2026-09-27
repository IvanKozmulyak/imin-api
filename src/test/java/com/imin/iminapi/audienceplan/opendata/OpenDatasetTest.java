package com.imin.iminapi.audienceplan.opendata;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenDatasetTest {

    @Test
    void keysRoundTripAndUnknownIsEmpty() {
        for (OpenDataset d : OpenDataset.values()) assertThat(OpenDataset.fromKey(d.key())).contains(d);
        assertThat(OpenDataset.fromKey("sirene")).isEmpty();
    }

    @Test
    void refreshCadenceIsYearlyForCensusAndStudentsAndSemiAnnualForIgss() {
        assertThat(OpenDataset.INSEE_AGE.ttl()).isEqualTo(Duration.ofDays(365));
        assertThat(OpenDataset.STUDENTS.ttl()).isEqualTo(Duration.ofDays(365));
        assertThat(OpenDataset.FRONTALIERS.ttl()).isEqualTo(Duration.ofDays(182));
    }

    @Test
    void licencesMatchTheSources() {
        assertThat(OpenDataset.INSEE_AGE.licence()).isEqualTo("Licence Ouverte 2.0");
        assertThat(OpenDataset.STUDENTS.licence()).isEqualTo("Licence Ouverte 2.0");
        assertThat(OpenDataset.FRONTALIERS.licence()).isEqualTo("CC0 1.0");
        assertThat(OpenDataset.OSM_VENUES.licence()).isEqualTo("ODbL 1.0");
        assertThat(OpenDataset.CENTROID.licence()).isEqualTo("CC0 1.0");
        assertThat(OpenDataset.CENTROID.attribution()).isEqualTo("Source : Wikidata");
    }

    @Test
    void centroidsHaveNoHeadlineAndOutliveTheCensus() {
        assertThat(OpenDataset.CENTROID.headlineField()).isNull();
        assertThat(OpenDataset.CENTROID.ttl()).isEqualTo(Duration.ofDays(3650));
    }

    @Test
    void frenchSourcesCoverFranceOnlyAndCentroidsCoverTheBorderCountries() {
        for (OpenDataset d : List.of(OpenDataset.INSEE_AGE, OpenDataset.STUDENTS, OpenDataset.FRONTALIERS,
                OpenDataset.OSM_VENUES)) {
            assertThat(d.covers("FR")).as(d.key()).isTrue();
            assertThat(d.covers("LU")).as(d.key()).isFalse();
            assertThat(d.covers("DE")).as(d.key()).isFalse();
        }
        assertThat(OpenDataset.CENTROID.covers("FR")).isTrue();
        assertThat(OpenDataset.CENTROID.covers("LU")).isTrue();
        assertThat(OpenDataset.CENTROID.covers("DE")).isTrue();
        assertThat(OpenDataset.CENTROID.covers("BE")).isFalse();
        assertThat(OpenDataset.CENTROID.covers(null)).isFalse();
    }
}
