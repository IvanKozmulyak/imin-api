package com.imin.iminapi.repository;

import com.imin.iminapi.model.GateSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface GateSessionRepository extends JpaRepository<GateSession, UUID> {
    Optional<GateSession> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    /**
     * Hard-delete every gate session for the org. Called from the credential
     * rotate flow so that rotating the gate password also revokes every
     * currently-issued device token — otherwise rotation would only block
     * <em>future</em> logins, leaving stolen/lost devices authenticated until
     * their natural {@code expiresAt}.
     *
     * <p>Bulk DELETE rather than a derived {@code deleteAllByOrgId} so we emit
     * a single SQL statement instead of a SELECT-then-DELETE-per-row.
     */
    @Modifying
    @Query("DELETE FROM GateSession s WHERE s.orgId = :orgId")
    int deleteAllByOrgId(@Param("orgId") UUID orgId);
}
