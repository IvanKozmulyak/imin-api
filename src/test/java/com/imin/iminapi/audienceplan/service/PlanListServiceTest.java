package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.security.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanListServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    void from_blankOrMissing_isNow() {
        assertThat(PlanListService.from(null, NOW)).isEqualTo(NOW);
        assertThat(PlanListService.from("  ", NOW)).isEqualTo(NOW);
    }

    @Test
    void from_inThePast_isRaisedToNow_andInTheFuture_isKept() {
        assertThat(PlanListService.from("2026-09-01T00:00:00Z", NOW)).isEqualTo(NOW);
        assertThat(PlanListService.from(" 2026-10-01T00:00:00Z ", NOW)).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void from_unparseable_is400OnTheFromField() {
        assertThatThrownBy(() -> PlanListService.from("2026-10-01", NOW))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(400);
                    assertThat(e.fields()).containsKey("from");
                });
    }
}
