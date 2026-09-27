package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Modes;
import com.imin.iminapi.audienceplan.engine.ModeSelector.Mode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModeSelectorTest {

    private static final Modes MODES = PlanFixtures.LOGIC.logic().modes();

    @Test
    void shippedThresholdsAre50And500() {
        assertThat(MODES).isEqualTo(new Modes(50, 500));
    }

    @Test
    void under50IsCold() {
        assertThat(ModeSelector.select(0, MODES)).isEqualTo(Mode.COLD);
        assertThat(ModeSelector.select(49, MODES)).isEqualTo(Mode.COLD);
    }

    @Test
    void from50To499IsWarm() {
        assertThat(ModeSelector.select(50, MODES)).isEqualTo(Mode.WARM);
        assertThat(ModeSelector.select(499, MODES)).isEqualTo(Mode.WARM);
    }

    @Test
    void from500IsHot() {
        assertThat(ModeSelector.select(500, MODES)).isEqualTo(Mode.HOT);
    }

    @Test
    void negativeIsRefused() {
        assertThatThrownBy(() -> ModeSelector.select(-1, MODES)).isInstanceOf(IllegalArgumentException.class);
    }
}
