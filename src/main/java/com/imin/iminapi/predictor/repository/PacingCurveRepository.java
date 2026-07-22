package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.PacingCurve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

/**
 * Store for the persisted pacing curves (spec §7 Stage 1, V73). Rebuilt whole by the daily
 * {@code PacingCurveService.rebuildAll} (delete-all + reinsert); read by-key on the re-forecast
 * path. {@code @RepositoryRestResource(exported = false)} keeps spring-data-rest from
 * auto-exposing it, matching every other repo here.
 */
@RepositoryRestResource(exported = false)
public interface PacingCurveRepository extends JpaRepository<PacingCurve, String> {
}
