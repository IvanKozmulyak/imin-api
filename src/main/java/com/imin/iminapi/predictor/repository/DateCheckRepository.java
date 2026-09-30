package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.DateCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/** Date checks, newest first per org. */
@RepositoryRestResource(exported = false)
public interface DateCheckRepository extends JpaRepository<DateCheck, UUID> {

    List<DateCheck> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);
}
