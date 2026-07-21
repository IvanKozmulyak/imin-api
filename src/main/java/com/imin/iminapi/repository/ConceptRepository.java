package com.imin.iminapi.repository;

import com.imin.iminapi.model.Concept;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

/**
 * Concept rows (children of {@code GeneratedEvent}). Added with V71 so the event-creation
 * path can verify a claimed {@code sourceConceptId} really belongs to the caller's org
 * before stamping {@code events.concept_ai_generated = true}.
 */
@RepositoryRestResource(exported = false)
public interface ConceptRepository extends JpaRepository<Concept, UUID> {

    /** The concept only if its parent GeneratedEvent belongs to the given org (no cross-org leak). */
    @Query("select c from Concept c where c.id = :id and c.generatedEvent.orgId = :orgId")
    Optional<Concept> findByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);
}
