package com.imin.iminapi.repository;

import com.imin.iminapi.model.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;

@RepositoryRestResource(exported = false)
public interface ProcessedWebhookEventRepository
        extends JpaRepository<ProcessedWebhookEvent, String> {

    /**
     * Prune dedup markers older than {@code cutoff}. Stripe only retries for ~3 days, so rows
     * past a generous retention serve no purpose — see
     * {@link com.imin.iminapi.stripe.ProcessedWebhookEventSweeper}.
     */
    @Modifying
    @Query("delete from ProcessedWebhookEvent e where e.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff);
}
