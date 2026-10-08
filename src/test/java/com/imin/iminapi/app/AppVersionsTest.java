package com.imin.iminapi.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionsTest {

    // Numeric not lexical ("1.10.0" > "1.9.0": a String.compareTo gate nags the freshest install forever),
    // equal versions, missing segments read as zero.
    @ParameterizedTest
    @CsvSource({
            "1.10.0, 1.9.0,   1",
            "1.9.0,  1.10.0, -1",
            "2.0.0,  1.99.99, 1",
            "1.2.3,  1.2.3,   0",
            "1.2,    1.2.0,   0",
            "1.3,    1.2.9,   1",
    })
    void comparesNumericallyWithMissingSegmentsAsZero(String a, String b, int sign) {
        assertThat(Integer.signum(AppVersions.compare(a, b))).isEqualTo(sign);
    }

    // An unparseable version must fail OPEN: a "too old" verdict bricks the app for
    // everyone whose header we failed to read.
    @ParameterizedTest
    @CsvSource(value = {"NIL, 1.0.0", "'', 1.0.0", "not-a-version, 1.0.0", "1.2.3-beta.1, 1.2.3"}, nullValues = "NIL")
    void junkNeverLocksAnybodyOut(String installed, String minimum) {
        assertThat(AppVersions.isAtLeast(installed, minimum)).isTrue();
    }
}
