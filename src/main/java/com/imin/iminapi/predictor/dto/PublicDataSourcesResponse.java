package com.imin.iminapi.predictor.dto;

import java.util.List;

/** {@code GET /api/v1/public/predictor/sources}: outside datasets the predictor uses today. */
public record PublicDataSourcesResponse(String reviewedOn, List<PublicDataSource> sources) {

    /**
     * One credited dataset; {@code status} is always {@code active}, planned sources are never listed.
     * {@code lastUpdated} is the ISO date of the latest sync of its stored rows, or null when there is none.
     */
    public record PublicDataSource(
            String id,
            String name,
            List<String> usedFor,
            String licence,
            String licenceUrl,
            String creditLine,
            String url,
            String status,
            String lastUpdated) {}
}
