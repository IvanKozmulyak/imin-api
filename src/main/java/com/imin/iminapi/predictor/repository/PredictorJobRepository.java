package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.PredictorJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Predictor background job queue. */
@RepositoryRestResource(exported = false)
public interface PredictorJobRepository extends JpaRepository<PredictorJob, UUID> {

    /** Queued jobs due at {@code now}, oldest due first. */
    @Query("""
            select j.id from PredictorJob j
             where j.status = 'queued' and j.runAfter <= :now
             order by j.runAfter asc, j.id asc""")
    List<UUID> findClaimable(@Param("now") Instant now, Pageable page);

    /** 1 when this caller took the job; the attempt is counted here, so an expired lease is one. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PredictorJob j
               set j.status = 'running', j.lockedUntil = :lockedUntil, j.attempts = j.attempts + 1,
                   j.updatedAt = :now
             where j.id = :id and j.status = 'queued' and j.runAfter <= :now""")
    int claim(@Param("id") UUID id, @Param("now") Instant now, @Param("lockedUntil") Instant lockedUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PredictorJob j
               set j.status = 'queued', j.lockedUntil = null, j.runAfter = :now, j.updatedAt = :now
             where j.status = 'running' and j.lockedUntil < :now and j.attempts < :maxAttempts""")
    int requeueExpired(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PredictorJob j
               set j.status = 'failed', j.lockedUntil = null, j.lastError = :error, j.updatedAt = :now
             where j.status = 'running' and j.lockedUntil < :now and j.attempts >= :maxAttempts""")
    int failExpired(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts,
                    @Param("error") String error);

    /** Ends a run only while the caller's own lease is on the row; 0 means the lease was lost. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PredictorJob j
               set j.status = :status, j.runAfter = :runAfter, j.lastError = :lastError,
                   j.lockedUntil = null, j.updatedAt = :now
             where j.id = :id and j.status = 'running' and j.lockedUntil = :lockedUntil""")
    int finish(@Param("id") UUID id, @Param("lockedUntil") Instant lockedUntil, @Param("status") String status,
               @Param("runAfter") Instant runAfter, @Param("lastError") String lastError,
               @Param("now") Instant now);
}
