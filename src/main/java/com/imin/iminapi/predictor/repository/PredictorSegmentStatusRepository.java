package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.PredictorSegmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

/** Per-segment accuracy/tripwire rows (V72). Keyed by '{@code <genre_family>|<capacity_band>}'. */
@RepositoryRestResource(exported = false)
public interface PredictorSegmentStatusRepository extends JpaRepository<PredictorSegmentStatus, String> {
}
