package com.imin.iminapi.predictor.dto;

import java.util.List;

/**
 * What the "Check a date" form may offer: genre buckets with sub-genres, and the request limits.
 * {@code researchAvailable} is true only for an org on the research list while research is on.
 */
public record DateCheckConfigResponse(boolean researchAvailable, List<Genre> genres, int maxDates,
                                      int maxHorizonMonths) {

    public record Genre(String bucket, List<String> subGenres) {}
}
