package com.imin.iminapi.repository;

import com.imin.iminapi.model.TicketTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface TicketTierRepository extends JpaRepository<TicketTier, UUID> {
    List<TicketTier> findByEventIdOrderBySortOrderAsc(UUID eventId);
}
