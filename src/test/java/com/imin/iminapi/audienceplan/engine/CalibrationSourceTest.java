package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.engine.CalibrationSource.Counts;
import com.imin.iminapi.audienceplan.engine.CalibrationSource.Observations;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalibrationSourceTest {

    @Test
    void none_returnsZeroObservations() {
        Observations obs = CalibrationSource.NONE.observations(UUID.randomUUID(), "loyal", ResponseModel.Fit.SAME);
        assertThat(obs).isSameAs(Observations.NONE);
        assertThat(obs.imin()).isEqualTo(new Counts(0, 0));
        assertThat(obs.own()).isEqualTo(new Counts(0, 0));
    }

    @Test
    void total_sumsIminAndOwn() {
        assertThat(new Observations(new Counts(6, 2), new Counts(4, 1)).total()).isEqualTo(new Counts(10, 3));
    }

    @Test
    void negativeInvited_isRejected() {
        assertThatThrownBy(() -> new Counts(-1, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invited must be >= 0: -1");
    }

    @Test
    void negativeBought_isRejected() {
        assertThatThrownBy(() -> new Counts(1, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("bought must be >= 0: -1");
    }

    @Test
    void boughtAboveInvited_isRejected() {
        assertThatThrownBy(() -> new Counts(3, 4))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("bought 4 exceeds invited 3");
    }

    @Test
    void boughtEqualToInvited_isAccepted() {
        assertThat(new Counts(4, 4).bought()).isEqualTo(4);
    }
}
