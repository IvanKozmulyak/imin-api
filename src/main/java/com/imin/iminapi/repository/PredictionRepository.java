package com.imin.iminapi.repository;

import com.imin.iminapi.model.Prediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface PredictionRepository extends JpaRepository<Prediction, UUID> {
}
