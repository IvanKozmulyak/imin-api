package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.TransitDisruption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stored traffic messages (V175); written only by PrimWriter. */
@RepositoryRestResource(exported = false)
public interface TransitDisruptionRepository extends JpaRepository<TransitDisruption, UUID> {

    /** Rows whose span overlaps {@code [from, to)}: {@code last_end > from AND first_begin < to}. */
    @Query("select d from TransitDisruption d where d.source = :source and d.lastEnd > :from and d.firstBegin < :to")
    List<TransitDisruption> findOverlapping(@Param("source") String source, @Param("from") Instant from,
                                            @Param("to") Instant to);

    /** A bulk delete, so it runs before the replacement inserts of the same transaction. */
    @Modifying
    @Query("delete from TransitDisruption d where d.source = :source")
    int deleteBySource(@Param("source") String source);
}
