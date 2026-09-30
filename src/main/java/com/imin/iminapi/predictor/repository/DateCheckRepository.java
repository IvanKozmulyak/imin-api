package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.DateCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Date checks, newest first per org. */
@RepositoryRestResource(exported = false)
public interface DateCheckRepository extends JpaRepository<DateCheck, UUID> {

    List<DateCheck> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);

    /** The newest check made for an event ({@code date_check.event_id}). */
    Optional<DateCheck> findFirstByOrgIdAndEventIdOrderByCreatedAtDescIdDesc(UUID orgId, UUID eventId);

    /**
     * Serialises re-scores of one check: its dates are replaced under a unique (check, date) key. Native
     * {@code FOR UPDATE}: PESSIMISTIC_WRITE renders {@code FOR NO KEY UPDATE}, which H2 cannot parse.
     */
    @Query(value = "SELECT * FROM date_check WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<DateCheck> findLockedById(@Param("id") UUID id);
}
