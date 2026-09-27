package com.imin.iminapi.audienceplan.service;

import java.util.List;

/**
 * Known towns within {@code radiusKm} straight-line km of a venue, nearest first.
 *
 * @param towns never empty: no town in range is no catchment at all, not an empty one
 */
public record Catchment(int radiusKm, List<Town> towns) {

    /**
     * @param kmStraight great-circle km from the venue to the town centre, rounded
     * @param ownScene   whether the town has its own scene of the genre; null until portraits exist
     */
    public record Town(String cityKey, String name, String country, int kmStraight, Boolean ownScene) {}
}
