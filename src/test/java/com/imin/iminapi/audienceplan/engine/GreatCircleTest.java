package com.imin.iminapi.audienceplan.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GreatCircleTest {

    private static final double METZ_LAT = 49.119722, METZ_LON = 6.176944;
    private static final double LUX_LAT = 49.611389, LUX_LON = 6.13;

    @Test
    void metzToLuxembourgIsAboutFiftyFiveKm() {
        assertThat(GreatCircle.km(METZ_LAT, METZ_LON, LUX_LAT, LUX_LON)).isCloseTo(54.78, within(0.05));
    }
}
