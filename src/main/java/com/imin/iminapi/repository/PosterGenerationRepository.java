package com.imin.iminapi.repository;

import com.imin.iminapi.model.PosterGeneration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface PosterGenerationRepository extends JpaRepository<PosterGeneration, UUID> {

    Optional<PosterGeneration> findTopByGeneratedEventIdOrderByCreatedAtDesc(UUID generatedEventId);

    /**
     * Newest-first generations for a concept WITH their variants eagerly fetched. The poster
     * pipeline runs outside any transaction (see {@code ConceptStudioService.run}), so a lazy
     * {@code getVariants()} on a detached row would throw; a regenerate that locks the poster
     * needs the prior variants and must read them in the query.
     */
    @Query("""
            SELECT g FROM PosterGeneration g
            LEFT JOIN FETCH g.variants
            WHERE g.generatedEventId = :generatedEventId
            ORDER BY g.createdAt DESC
            """)
    List<PosterGeneration> findWithVariantsByGeneratedEventId(@Param("generatedEventId") UUID generatedEventId);
}
