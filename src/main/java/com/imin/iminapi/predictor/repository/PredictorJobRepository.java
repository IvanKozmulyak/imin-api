package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.PredictorJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

/** Predictor background job queue. */
@RepositoryRestResource(exported = false)
public interface PredictorJobRepository extends JpaRepository<PredictorJob, UUID> {
}
