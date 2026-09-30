package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.DateCheckFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Findings of one or more candidate dates. */
@RepositoryRestResource(exported = false)
public interface DateCheckFindingRepository extends JpaRepository<DateCheckFinding, UUID> {

    List<DateCheckFinding> findByDateCheckDateIdIn(Collection<UUID> dateCheckDateIds);
}
