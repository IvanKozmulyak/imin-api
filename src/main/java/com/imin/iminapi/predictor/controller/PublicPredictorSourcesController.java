package com.imin.iminapi.predictor.controller;

import com.imin.iminapi.predictor.dto.PublicDataSourcesResponse;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.SourceSyncDates;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/public/predictor/sources}: credits for the outside datasets in use now.
 * Unauthenticated via the {@code GET /api/v1/public/**} permitAll; no rate-limit bucket, like the other cacheable GETs.
 */
@RestController
@RequestMapping("/api/v1/public/predictor")
public class PublicPredictorSourcesController {

    static final String CACHE_CONTROL = "public, s-maxage=300, stale-while-revalidate=60";

    private final DataSourceCatalog catalog;
    private final SourceSyncDates syncDates;

    public PublicPredictorSourcesController(DataSourceCatalog catalog, SourceSyncDates syncDates) {
        this.catalog = catalog;
        this.syncDates = syncDates;
    }

    @GetMapping("/sources")
    public ResponseEntity<PublicDataSourcesResponse> sources() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(new PublicDataSourcesResponse(catalog.reviewedOn(), catalog.active(syncDates::lastUpdated)));
    }
}
