package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.OrgConnector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

/** An org's outside-account connectors. */
@RepositoryRestResource(exported = false)
public interface OrgConnectorRepository extends JpaRepository<OrgConnector, UUID> {

    List<OrgConnector> findByOrgIdAndRevokedAtIsNull(UUID orgId);
}
