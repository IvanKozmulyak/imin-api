package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Modes;
import com.imin.iminapi.audienceplan.engine.ModeSelector.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModeSelectorTest {

    private static final Modes MODES = PlanFixtures.LOGIC.logic().modes();

    @ParameterizedTest
    @CsvSource({"0, COLD", "49, COLD", "50, WARM", "499, WARM", "500, HOT"})
    void boundaries_pickTheMode(int audience, Mode expected) {
        assertThat(ModeSelector.select(audience, MODES)).isEqualTo(expected);
    }

    @Test
    void negativeIsRefused() {
        assertThatThrownBy(() -> ModeSelector.select(-1, MODES)).isInstanceOf(IllegalArgumentException.class);
    }
}
