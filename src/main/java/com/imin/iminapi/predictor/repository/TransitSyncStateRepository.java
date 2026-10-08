package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.TransitSyncState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

/** One sync-state row per transit source (V175); written only by PrimWriter and TransitStopStore. */
@RepositoryRestResource(exported = false)
public interface TransitSyncStateRepository extends JpaRepository<TransitSyncState, String> {
}
