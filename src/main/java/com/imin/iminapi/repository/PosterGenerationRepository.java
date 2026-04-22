package com.imin.iminapi.repository;

import com.imin.iminapi.model.PosterGeneration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PosterGenerationRepository extends JpaRepository<PosterGeneration, UUID> {}
