package com.imin.iminapi.audienceplan.opendata;

import java.util.Map;

/**
 * One figure set fetched from a source.
 *
 * @param headline  the dataset's main number; null when the dataset has none
 * @param figures   every extracted number, stored as the row's JSON payload
 */
public record FetchedFigure(String refPeriod, Long headline, Map<String, Object> figures, String sourceUrl) {}
