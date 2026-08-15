package com.imin.iminapi.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionsTest {

    @Test
    void comparesNumericallyNotLexically() {
        // The whole reason this class exists.
        assertThat(AppVersions.compare("1.10.0", "1.9.0")).isPositive();
        assertThat(AppVersions.compare("1.9.0", "1.10.0")).isNegative();
        assertThat(AppVersions.compare("2.0.0", "1.99.99")).isPositive();
    }

    @Test
    void equalVersionsCompareEqual() {
        assertThat(AppVersions.compare("1.2.3", "1.2.3")).isZero();
    }

    @Test
    void missingSegmentsReadAsZero() {
        assertThat(AppVersions.compare("1.2", "1.2.0")).isZero();
        assertThat(AppVersions.compare("1.3", "1.2.9")).isPositive();
    }

    @Test
    void junkNeverLocksAnybodyOut() {
        // An unparseable version must fail OPEN. A crash or a "too old" verdict
        // here bricks the app for everyone whose header we failed to read.
        assertThat(AppVersions.isAtLeast(null, "1.0.0")).isTrue();
        assertThat(AppVersions.isAtLeast("", "1.0.0")).isTrue();
        assertThat(AppVersions.isAtLeast("not-a-version", "1.0.0")).isTrue();
        assertThat(AppVersions.isAtLeast("1.2.3-beta.1", "1.2.3")).isTrue();
    }
}
