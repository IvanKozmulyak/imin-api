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

    /** The team list — removed accounts (V118) stay in the table but leave the org's roster. */
    List<User> findByOrgIdAndDisabledAtIsNullOrderByCreatedAtAsc(UUID orgId);

    /**
     * Rows that would make a hard delete of this user raise a 23503.
     *
     * <p>Exactly the three FKs on {@code users} that carry no {@code ON DELETE}
     * clause: {@code events.created_by} (V6:31),
     * {@code refunds.initiated_by_user_id} (V28:33) and
     * {@code refund_requests.decided_by_user_id} (V30:17). Everything else
     * pointing at {@code users} cascades. Native and count-based rather than
     * three JPQL {@code exists} calls so it stays one round trip and does not
     * need a repository method in packages this query has no other business in.
     */
    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT 1 FROM events           WHERE created_by           = :userId
                UNION ALL
                SELECT 1 FROM refunds          WHERE initiated_by_user_id = :userId
                UNION ALL
                SELECT 1 FROM refund_requests  WHERE decided_by_user_id   = :userId
            ) referencing
            """, nativeQuery = true)
    long countRetainedReferences(@Param("userId") UUID userId);
}
