package com.imin.iminapi.repository;

import com.imin.iminapi.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailLower(String emailLower);
    boolean existsByEmailLower(String emailLower);
    List<User> findByOrgIdOrderByCreatedAtAsc(UUID orgId);

    /**
     * Row-lock one user for the rest of the current transaction.
     *
     * <p>Used only by {@code AiQuotaService} to serialise its per-user check-then-insert, which
     * has no natural row of its own to lock and no DB-level uniqueness to lean on. The lock is
     * held for three short statements; nothing else in the codebase locks users, so there is no
     * ordering to deadlock against. Native and plain {@code FOR UPDATE} on purpose: JPA's
     * PESSIMISTIC_WRITE renders as PostgreSQL's {@code FOR NO KEY UPDATE}, which H2 — the test
     * database — cannot parse, and a lock the tests cannot execute is a lock nothing proves.
     */
    @Query(value = "select id from users where id = :id for update", nativeQuery = true)
    Optional<UUID> lockForUpdate(@Param("id") UUID id);
}
