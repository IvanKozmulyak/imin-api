package com.imin.iminapi.repository;

import com.imin.iminapi.model.OrderRecoveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface OrderRecoveryAttemptRepository extends JpaRepository<OrderRecoveryAttempt, UUID> {
    long countByEmailAndAttemptedAtAfter(String email, Instant cutoff);
    long countByIpHashAndAttemptedAtAfter(String ipHash, Instant cutoff);
}
