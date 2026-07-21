package com.imin.iminapi.repository;

import com.imin.iminapi.model.AiGenerationUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AiGenerationUsageRepository extends JpaRepository<AiGenerationUsage, UUID> {

    /** Attempts for a user of one kind inside the rolling window (created after {@code after}). */
    long countByUserIdAndKindAndCreatedAtAfter(UUID userId, String kind, Instant after);

    /**
     * Oldest attempt timestamp still inside the window — the row whose expiry frees the next
     * slot. {@code resetAt = this + 24h}. Null when the window is empty.
     */
    @Query("select min(u.createdAt) from AiGenerationUsage u " +
            "where u.userId = :userId and u.kind = :kind and u.createdAt > :after")
    Instant findOldestCreatedAt(@Param("userId") UUID userId,
                                @Param("kind") String kind,
                                @Param("after") Instant after);
}
