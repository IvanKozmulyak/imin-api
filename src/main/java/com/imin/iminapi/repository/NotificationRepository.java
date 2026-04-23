package com.imin.iminapi.repository;

import com.imin.iminapi.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    long countByUserIdAndReadAtIsNull(UUID userId);
}
