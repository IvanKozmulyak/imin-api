package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.opendata.OpenDataset;

import java.time.Instant;
import java.util.Map;

/**
 * A cached open-data figure set with its provenance.
 *
 * @param headline the dataset's main number, or null when unknown (never 0 for unknown)
 * @param figures  every stored number by field; a null value means the source did not give it
 * @param stale    true when the row is past {@code expires_at} and a refresh has not succeeded
 */
public record OpenDataValue(String cityKey, OpenDataset dataset, String refPeriod, Long headline,
                            Map<String, Long> figures, String sourceUrl, String licence, String attribution,
                            Instant fetchedAt, boolean stale) {}
