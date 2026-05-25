package com.imin.iminapi.repository;

import com.imin.iminapi.model.GateSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface GateSessionRepository extends JpaRepository<GateSession, UUID> {
    Optional<GateSession> findByTokenHashAndRevokedAtIsNull(String tokenHash);
}
