package com.imin.iminapi.repository;

import com.imin.iminapi.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailLower(String emailLower);
    boolean existsByEmailLower(String emailLower);
}
