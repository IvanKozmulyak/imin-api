package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.engine.CalibrationSource.Counts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalibrationSourceTest {

    @ParameterizedTest
    @CsvSource({
            "-1, 0, 'invited must be >= 0: -1'",
            "1, -1, 'bought must be >= 0: -1'",
            "3, 4, 'bought 4 exceeds invited 3'"})
    void impossibleCounts_areRejected(int invited, int bought, String message) {
        assertThatThrownBy(() -> new Counts(invited, bought))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void boughtEqualToInvited_isAccepted() {
        assertThat(new Counts(4, 4).bought()).isEqualTo(4);
    }
}
