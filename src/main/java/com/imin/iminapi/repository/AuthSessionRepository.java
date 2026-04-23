package com.imin.iminapi.repository;

import com.imin.iminapi.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    Optional<AuthSession> findByTokenHashAndRevokedAtIsNull(String tokenHash);
}
