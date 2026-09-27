package com.imin.iminapi.audienceplan.opendata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenDataCsvTest {

    @Test
    void aRowWithTheWrongColumnCountFailsTheLoad() {
        assertThatThrownBy(() -> OpenDataCsv.read("audienceplan/open-data-test/bad_columns.csv"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("line 2");
    }

    @Test
    void blankCellsAndBlankLinesAreNullAndSkipped() {
        assertThat(OpenDataCsv.read("audienceplan/open-data-test/blank_cells.csv"))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("a")).isEqualTo("1");
                    assertThat(row.get("b")).isNull();
                });
    }

    @Test
    void aMissingFileFails() {
        assertThatThrownBy(() -> OpenDataCsv.read("audienceplan/open-data-test/nope.csv"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing");
    }

    @Test
    void aCityWhoseKeyIsNotItsEventCityKeyIsRejected() {
        assertThatThrownBy(() -> OpenDataCities.load("audienceplan/open-data-test/bad_city_key.csv"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("key metz-centre does not match Metz");
    }
}
