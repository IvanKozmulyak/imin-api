package com.imin.iminapi.predictor.calendar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarSyncPropertiesTest {

    private final CalendarSyncProperties props = new CalendarSyncProperties();

    @Test
    void blankSyncEnabledStaysOn() {
        props.setSyncEnabled(null);

        assertThat(props.getSyncEnabled()).isTrue();
    }

    @Test
    void explicitFalseTurnsSyncOff() {
        props.setSyncEnabled(false);

        assertThat(props.getSyncEnabled()).isFalse();
    }

    @Test
    void blankYearsAheadFallsBackToDefault() {
        props.setYearsAhead(null);

        assertThat(props.getYearsAhead()).isEqualTo(CalendarSyncProperties.DEFAULT_YEARS_AHEAD);
    }

    @Test
    void negativeYearsAheadFallsBackToDefault() {
        props.setYearsAhead(-1);

        assertThat(props.getYearsAhead()).isEqualTo(CalendarSyncProperties.DEFAULT_YEARS_AHEAD);
    }

    @Test
    void zeroYearsAheadMeansCurrentYearOnly() {
        props.setYearsAhead(0);

        assertThat(props.getYearsAhead()).isZero();
    }
}
