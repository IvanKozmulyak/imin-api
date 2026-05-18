package com.imin.iminapi.repository;

import com.imin.iminapi.model.NotifySubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface NotifySubscriptionRepository extends JpaRepository<NotifySubscription, UUID> {

    Optional<NotifySubscription> findByEventIdAndEmail(UUID eventId, String email);
}
