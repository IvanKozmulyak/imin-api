package com.imin.iminapi.audienceplan.engine;

/** Straight-line (great-circle) distance between two points given in decimal degrees. */
public final class GreatCircle {

    /** Mean Earth radius (IUGG), km. */
    static final double EARTH_RADIUS_KM = 6371.0088;

    private GreatCircle() {}

    /** Haversine distance in km. */
    public static double km(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
