package com.imin.iminapi.audienceplan.opendata;

import org.junit.jupiter.api.Test;

import java.time.Duration;

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
    }
}
