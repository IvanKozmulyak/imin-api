package com.imin.iminapi.audienceplan.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class PlanServiceTest {

    @ParameterizedTest
    @CsvSource(value = {"null, Z", "'  ', Z", "Mars/Olympus, Z", "Europe/Paris, Europe/Paris"},
            nullValues = "null")
    void zone_readsTheEventZone_andFallsBackToUtc(String raw, String expected) {
        assertThat(PlanService.zone(raw)).isEqualTo(ZoneId.of(expected));
    }
}
