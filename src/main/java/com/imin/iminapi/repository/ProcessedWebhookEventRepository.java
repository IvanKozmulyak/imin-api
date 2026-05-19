package com.imin.iminapi.repository;

import com.imin.iminapi.model.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ProcessedWebhookEventRepository
        extends JpaRepository<ProcessedWebhookEvent, String> {
}
