package com.imin.iminapi.predictor.calendar;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** A blank Railway variable binds null; the setters must turn it into the safe default. */
class CalendarSyncPropertiesTest {

    private final CalendarSyncProperties props = new CalendarSyncProperties();

    @ParameterizedTest(name = "syncEnabled={0} -> {1}")
    @CsvSource(nullValues = "null", value = {"null, true", "false, false"})
    void blankSyncEnabledStaysOn(Boolean value, boolean expected) {
        props.setSyncEnabled(value);

        assertThat(props.getSyncEnabled()).isEqualTo(expected);
    }

    // -1 means the default; 0 means the current year only
    @ParameterizedTest(name = "yearsAhead={0} -> {1}")
    @CsvSource(nullValues = "null", value = {"null, " + CalendarSyncProperties.DEFAULT_YEARS_AHEAD,
            "-1, " + CalendarSyncProperties.DEFAULT_YEARS_AHEAD, "0, 0"})
    void blankOrNegativeYearsAheadFallsBackToDefault(Integer value, int expected) {
        props.setYearsAhead(value);

        assertThat(props.getYearsAhead()).isEqualTo(expected);
    }
}
