package com.imin.iminapi.repository;

import com.imin.iminapi.model.TicketTierMilestone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface TicketTierMilestoneRepository extends JpaRepository<TicketTierMilestone, UUID> {
    boolean existsByTierIdAndThreshold(UUID tierId, int threshold);
}
