package com.imin.iminapi.audienceplan.service;

import java.util.List;

/**
 * People in a genre's tribe for one or more cities: genre-first people and the regulars among them.
 * Sizes are ranges from open data × research rates; an unknown size is null, never 0.
 */
public record TribeSize(String genreKey, List<String> cityKeys, Estimate genreFirst, Estimate regulars) {

    /**
     * @param low     rounded low size, or null when an input is unknown
     * @param method  the product of input keys that gives the size
     */
    public record Estimate(Long low, Long high, String method, List<Input> inputs, List<Source> sources) {}

    /**
     * A factor of the product. The population input has {@code cityKey} set and low = high, or both null when
     * the census row is missing; a rate input has {@code cityKey} null.
     */
    public record Input(String key, String cityKey, Double low, Double high) {}

    /**
     * Where an input comes from. {@code period} is the census period or the study year, null for a derived rate
     * (then {@code note} says how it was derived). {@code stale} marks a census row past its refresh date.
     */
    public record Source(String input, String cityKey, String label, String period, String url, String note,
                         boolean stale) {}
}
