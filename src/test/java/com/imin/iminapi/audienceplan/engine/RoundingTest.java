package com.imin.iminapi.audienceplan.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoundingTest {

    @Test
    void count_roundsHalfUp() {
        assertThat(Rounding.count(7.68)).isEqualTo(8);
        assertThat(Rounding.count(13.44)).isEqualTo(13);
        assertThat(Rounding.count(2.5)).isEqualTo(3);
        assertThat(Rounding.count(0)).isZero();
    }

    @Test
    void count_refusesNegativeAndNaN() {
        assertThatThrownBy(() -> Rounding.count(-0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rounding.count(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ratio2_truncatesToTwoDecimals() {
        assertThat(Rounding.ratio2(93, 255)).isEqualTo(0.36);
        assertThat(Rounding.ratio2(52, 255)).isEqualTo(0.20);
        assertThat(Rounding.ratio2(26, 255)).isEqualTo(0.10);
        assertThat(Rounding.ratio2(1, 8)).isEqualTo(0.12);
        assertThat(Rounding.ratio2(1496, 10000)).isEqualTo(0.14);
        assertThat(Rounding.ratio2(299, 1000)).isEqualTo(0.29);
        assertThat(Rounding.ratio2(150, 1000)).isEqualTo(0.15);
    }

    @Test
    void ratio2_refusesAZeroDenominator() {
        assertThatThrownBy(() -> Rounding.ratio2(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentOf_roundsHalfUpInIntegers() {
        assertThat(Rounding.percentOf(300, 85)).isEqualTo(255);
        assertThat(Rounding.percentOf(301, 85)).isEqualTo(256);
        assertThat(Rounding.percentOf(10, 5)).isEqualTo(1);
        assertThat(Rounding.percentOf(9, 5)).isZero();
    }

    @Test
    void percentOf_refusesNegatives() {
        assertThatThrownBy(() -> Rounding.percentOf(-1, 85)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rounding.percentOf(300, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
