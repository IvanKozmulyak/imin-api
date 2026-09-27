package com.imin.iminapi.audience.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateRangeTest {

    @Test
    void nothingToDivideBy_isNull() {
        assertThat(RateRange.of(0, 0)).isNull();
    }

    @Test
    void midIsTheShare_boundsAreTheBeta80PctInterval_inPercentToOneDecimal() {
        RateRange r = RateRange.of(1, 2);

        assertThat(r.mid()).isEqualTo(50.0);
        assertThat(r.n()).isEqualTo(2);
        // Beta(2, 2): p10 = 0.1958, p90 = 0.8042.
        assertThat(r.low()).isEqualTo(19.6);
        assertThat(r.high()).isEqualTo(80.4);
    }

    @Test
    void largeSample_narrowsTheRange() {
        RateRange small = RateRange.of(1, 2);
        RateRange large = RateRange.of(500, 1000);

        assertThat(large.high() - large.low()).isLessThan(small.high() - small.low());
        assertThat(large.low()).isLessThanOrEqualTo(50.0);
        assertThat(large.high()).isGreaterThanOrEqualTo(50.0);
    }

    @Test
    void noHits_keepsLowAtZero_andMidAtZero() {
        RateRange r = RateRange.of(0, 5);

        assertThat(r.low()).isZero();
        assertThat(r.mid()).isZero();
        assertThat(r.high()).isGreaterThan(0);
    }

    @Test
    void hitsAboveTheBase_areClampedToTheBase() {
        assertThat(RateRange.of(7, 5).mid()).isEqualTo(100.0);
    }
}
