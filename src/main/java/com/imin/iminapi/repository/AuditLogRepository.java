package com.imin.iminapi.repository;

import com.imin.iminapi.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByOrgIdOrderByOccurredAtDesc(UUID orgId, Pageable pageable);

    /**
     * Art.17: drop the actor's address from this org's audit trail, keeping the
     * row.
     *
     * <p>An audit log is deliberately immutable and is the record that proves the
     * erasure itself was performed — deleting rows to satisfy an erasure would
     * destroy the evidence of the erasure. {@code actor_email} is the only free
     * personal identifier on the row; {@code actor_id} is a UUID whose own
     * {@code users} row goes when that account does, and {@code summary} is a
     * fixed system string.
     *
     * <p>Matched on the lowercased address, org-scoped, non-null parameter (the
     * H2-vs-Postgres {@code lower(bytea)} trap bites nullable String params).
     */
    @Modifying
    @Query("update AuditLog a set a.actorEmail = null "
            + "where a.orgId = :orgId and lower(a.actorEmail) = :email")
    int redactActorEmail(@Param("orgId") UUID orgId, @Param("email") String email);
}
