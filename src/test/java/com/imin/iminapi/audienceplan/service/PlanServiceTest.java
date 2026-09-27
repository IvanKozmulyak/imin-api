package com.imin.iminapi.audienceplan.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PlanServiceTest {

    @Test
    void zone_nullOrBlank_isUtc() {
        assertThat(PlanService.zone(null)).isEqualTo(ZoneOffset.UTC);
        assertThat(PlanService.zone("  ")).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void zone_unreadable_fallsBackToUtc() {
        assertThat(PlanService.zone("Mars/Olympus")).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void zone_valid_isKept() {
        assertThat(PlanService.zone("Europe/Paris")).isEqualTo(ZoneId.of("Europe/Paris"));
    }
}
