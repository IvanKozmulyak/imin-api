package com.imin.iminapi.predictor.sources.prim;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TransitStopStoreTest {

    private static final double LAT = 48.8606;
    private static final double LNG = 2.3376;
    private static final int RADIUS = 800;
    /** Mean Earth radius, the sphere the offsets below are laid out on. */
    private static final double R = 6_371_008.8;

    /** Latitude of a point {@code m} metres due north. */
    private static double north(double m) {
        return LAT + Math.toDegrees(m / R);
    }

    /** Longitude of a point {@code m} metres (great circle) due east, at the same latitude. */
    private static double east(double m) {
        return LNG + Math.toDegrees(2 * Math.asin(Math.sin(m / (2 * R)) / Math.cos(Math.toRadians(LAT))));
    }

    @ParameterizedTest(name = "{0} {1} m in={2}")
    @CsvSource({"east, 799, true", "east, 801, false", "north, 799, true", "north, 801, false"})
    void radius(String direction, double m, boolean in) {
        double lat = direction.equals("north") ? north(m) : LAT;
        double lng = direction.equals("east") ? east(m) : LNG;

        assertThat(TransitStopStore.meters(LAT, LNG, lat, lng) <= RADIUS).isEqualTo(in);
        // the bounding box is the SQL prefilter: a point inside the circle must be inside it too
        if (in) assertThat(TransitStopStore.box(LAT, LNG, RADIUS).contains(lat, lng)).isTrue();
    }
}
